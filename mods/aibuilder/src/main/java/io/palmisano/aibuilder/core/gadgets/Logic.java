package io.palmisano.aibuilder.core.gadgets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Two-level logic for the gadgets' logic arrays (spec v2 §5.3): each output as an OR of products of the inputs. The
 * candidates are the Quine–McCluskey primes of each output and of each pair of outputs' common on-set (so products can
 * be shared); a greedy cover then takes, each time, the candidate that covers the most of what's still uncovered among
 * the outputs it may serve (it covers none of their off-set), fewest literals first on a tie. Not always the fewest
 * products, but exact, and close on functions this small. Pure.
 */
public final class Logic {
	/** A product of literals: an input in {@code mask} doesn't matter, the others must be their bit in {@code value}; it feeds {@code outputs}. */
	public record Product(int value, int mask, Set<Integer> outputs) {
		public Product {
			value &= ~mask;
			outputs = Set.copyOf(outputs);
		}

		public boolean covers(int input) {
			return (input & ~mask) == value;
		}

		/** Input {@code i} is one of its literals. */
		public boolean uses(int i) {
			return (mask >> i & 1) == 0;
		}

		/** Input {@code i}'s literal wants it on (it's off for a complemented literal). */
		public boolean wants(int i) {
			return (value >> i & 1) == 1;
		}
	}

	/** A candidate product, before it's given its outputs. */
	private record Term(int value, int mask) {
		boolean covers(int input) {
			return (input & ~mask) == value;
		}

		int literals(int inputs) {
			return inputs - Integer.bitCount(mask);
		}
	}

	private Logic() {
	}

	/** Products giving each of {@code on} (an output's inputs that turn it on) exactly, {@code dontCare} inputs either way. */
	public static List<Product> minimize(int inputs, List<Set<Integer>> on, Set<Integer> dontCare) {
		int size = 1 << inputs;
		List<Set<Integer>> off = new ArrayList<>();
		for (Set<Integer> lit : on) {
			Set<Integer> o = new HashSet<>();
			for (int v = 0; v < size; v++) {
				if (!lit.contains(v) && !dontCare.contains(v)) {
					o.add(v);
				}
			}
			off.add(o);
		}
		Comparator<Term> order = Comparator.comparingInt((Term t) -> t.literals(inputs)).thenComparingInt(Term::mask).thenComparingInt(Term::value);
		Set<Term> candidates = new TreeSet<>(order);
		for (int o = 0; o < on.size(); o++) {
			candidates.addAll(primes(on.get(o), dontCare));
			for (int p = o + 1; p < on.size(); p++) {
				Set<Integer> both = new HashSet<>(on.get(o));
				both.retainAll(on.get(p));
				if (!both.isEmpty()) {
					candidates.addAll(primes(both, dontCare));
				}
			}
		}
		// What's still to cover: each output's on-set, as output * size + input.
		Set<Integer> uncovered = new LinkedHashSet<>();
		for (int o = 0; o < on.size(); o++) {
			for (int v : on.get(o)) {
				uncovered.add(o * size + v);
			}
		}
		List<Product> chosen = new ArrayList<>();
		while (!uncovered.isEmpty()) {
			Term best = null;
			Set<Integer> bestGain = Set.of();
			Set<Integer> bestOutputs = Set.of();
			for (Term term : candidates) {
				Set<Integer> gain = new HashSet<>();
				Set<Integer> outputs = new HashSet<>();
				for (int o = 0; o < on.size(); o++) {
					if (off.get(o).stream().anyMatch(term::covers)) {
						continue;
					}
					for (int v : on.get(o)) {
						if (term.covers(v) && uncovered.contains(o * size + v)) {
							gain.add(o * size + v);
							outputs.add(o);
						}
					}
				}
				if (gain.size() > bestGain.size()) {
					best = term;
					bestGain = gain;
					bestOutputs = outputs;
				}
			}
			if (best == null) {
				throw new IllegalStateException("no product covers what's left: " + uncovered);
			}
			chosen.add(new Product(best.value(), best.mask(), bestOutputs));
			uncovered.removeAll(bestGain);
		}
		chosen = pruned(chosen, on, size);
		// One product an input value is exact too: on a function with few such values it can be smaller.
		Set<Integer> needed = new TreeSet<>();
		on.forEach(needed::addAll);
		if (needed.size() < chosen.size()) {
			List<Product> minterms = new ArrayList<>();
			for (int v : needed) {
				Set<Integer> outputs = new HashSet<>();
				for (int o = 0; o < on.size(); o++) {
					if (on.get(o).contains(v)) {
						outputs.add(o);
					}
				}
				minterms.add(new Product(v, 0, outputs));
			}
			return minterms;
		}
		return chosen;
	}

	/** Without each product that the others cover for every output it feeds, last chosen first. */
	private static List<Product> pruned(List<Product> chosen, List<Set<Integer>> on, int size) {
		List<Product> kept = new ArrayList<>(chosen);
		for (int i = kept.size() - 1; i >= 0; i--) {
			Product p = kept.get(i);
			boolean spare = true;
			for (int o : p.outputs()) {
				for (int v : on.get(o)) {
					if (p.covers(v) && !coveredWithout(kept, i, o, v)) {
						spare = false;
					}
				}
			}
			if (spare) {
				kept.remove(i);
			}
		}
		return kept;
	}

	private static boolean coveredWithout(List<Product> products, int skip, int output, int value) {
		for (int k = 0; k < products.size(); k++) {
			if (k != skip && products.get(k).outputs().contains(output) && products.get(k).covers(value)) {
				return true;
			}
		}
		return false;
	}

	/** The prime implicants of {@code minterms} with {@code dontCare}, by merging terms that differ in one bit. */
	private static Set<Term> primes(Set<Integer> minterms, Set<Integer> dontCare) {
		Set<Term> terms = new HashSet<>();
		for (int m : minterms) {
			terms.add(new Term(m, 0));
		}
		for (int m : dontCare) {
			terms.add(new Term(m, 0));
		}
		Set<Term> primes = new HashSet<>();
		while (!terms.isEmpty()) {
			Set<Term> next = new HashSet<>();
			Set<Term> merged = new HashSet<>();
			List<Term> list = new ArrayList<>(terms);
			for (int i = 0; i < list.size(); i++) {
				for (int j = i + 1; j < list.size(); j++) {
					Term a = list.get(i);
					Term b = list.get(j);
					int differ = a.value() ^ b.value();
					if (a.mask() == b.mask() && Integer.bitCount(differ) == 1) {
						next.add(new Term(a.value() & ~differ, a.mask() | differ));
						merged.add(a);
						merged.add(b);
					}
				}
			}
			for (Term t : terms) {
				if (!merged.contains(t)) {
					primes.add(t);
				}
			}
			terms = next;
		}
		// A prime of don't-cares alone covers nothing that's needed.
		primes.removeIf(p -> minterms.stream().noneMatch(p::covers));
		return primes;
	}
}
