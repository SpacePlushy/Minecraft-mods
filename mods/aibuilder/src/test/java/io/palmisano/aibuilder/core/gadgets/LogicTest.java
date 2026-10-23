package io.palmisano.aibuilder.core.gadgets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/** Two-level logic for the gadgets' logic arrays (spec v2 §5.3): products found by Quine–McCluskey, shared by outputs. */
class LogicTest {
	private static final String[] SEGMENTS = {"abcdef", "bc", "abdeg", "abcdg", "bcfg", "acdfg", "acdefg", "abc", "abcdefg", "abcdfg"};

	/** Random functions of 3 and 4 inputs, with don't-cares: each output is the OR of its products, exactly. */
	@Test
	void everyFunctionIsCoveredExactly() {
		Random random = new Random(7);
		for (int round = 0; round < 300; round++) {
			int inputs = 2 + random.nextInt(3);
			int size = 1 << inputs;
			Set<Integer> dontCare = new HashSet<>();
			for (int v = 0; v < size; v++) {
				if (random.nextInt(6) == 0) {
					dontCare.add(v);
				}
			}
			List<Set<Integer>> on = new ArrayList<>();
			int outputs = 1 + random.nextInt(4);
			for (int o = 0; o < outputs; o++) {
				Set<Integer> set = new HashSet<>();
				for (int v = 0; v < size; v++) {
					if (!dontCare.contains(v) && random.nextBoolean()) {
						set.add(v);
					}
				}
				on.add(set);
			}
			List<Logic.Product> products = Logic.minimize(inputs, on, dontCare);
			for (int o = 0; o < outputs; o++) {
				for (int v = 0; v < size; v++) {
					if (dontCare.contains(v)) {
						continue;
					}
					int output = o;
					int value = v;
					boolean lit = products.stream().anyMatch(p -> p.outputs().contains(output) && p.covers(value));
					assertEquals(on.get(o).contains(v), lit, "round " + round + ", output " + o + ", input " + v + ": " + products);
				}
			}
		}
	}

	/** The sizes measured when the plan was made: what decides how deep each gadget is. */
	@Test
	void theSizesAreAsMeasured() {
		List<Set<Integer>> adder = List.of(where(8, v -> Integer.bitCount(v) % 2 == 1), where(8, v -> Integer.bitCount(v) >= 2));
		assertTrue(exact(3, adder, Set.of()) <= 7, "a full adder");
		assertTrue(exact(4, segments(10, v -> v), where(16, v -> v >= 10)) <= 9, "a BCD digit");
		assertTrue(exact(2, segments(4, v -> v), Set.of()) <= 4, "a 0-3 digit");
		List<Set<Integer>> decimal = new ArrayList<>();
		for (int bit = 1; bit <= 3; bit++) {
			int b = bit;
			decimal.add(where(16, m -> (2 * (m % 5) >> b & 1) == 1));
		}
		for (int bit = 0; bit <= 1; bit++) {
			int b = bit;
			decimal.add(where(16, m -> (m / 5 >> b & 1) == 1));
		}
		assertTrue(exact(4, decimal, Set.of()) <= 15, "tens and ones of 4 bits over 2");
	}

	/** How many products the function takes, once they're checked to give it exactly. */
	private static int exact(int inputs, List<Set<Integer>> on, Set<Integer> dontCare) {
		List<Logic.Product> products = Logic.minimize(inputs, on, dontCare);
		for (int o = 0; o < on.size(); o++) {
			for (int v = 0; v < 1 << inputs; v++) {
				int output = o;
				int value = v;
				if (!dontCare.contains(v)) {
					assertEquals(on.get(o).contains(v), products.stream().anyMatch(p -> p.outputs().contains(output) && p.covers(value)),
							"output " + o + ", input " + v);
				}
			}
		}
		return products.size();
	}

	/** A product's literals: an input it doesn't mask is wanted as its bit in the value. */
	@Test
	void aProductSaysWhichLiteralsItHas() {
		Logic.Product p = new Logic.Product(0b101, 0b010, Set.of(0));
		assertTrue(p.covers(0b101) && p.covers(0b111));
		assertTrue(!p.covers(0b100) && !p.covers(0b001));
		assertTrue(p.uses(0) && !p.uses(1) && p.uses(2));
		assertTrue(p.wants(0) && !p.wants(1) && p.wants(2));
	}

	private static Set<Integer> where(int size, IntPredicate test) {
		Set<Integer> out = new HashSet<>();
		IntStream.range(0, size).filter(test).forEach(out::add);
		return out;
	}

	/** Seven on-sets, segments a to g, of the digits {@code digit(v)} for v below {@code values}. */
	private static List<Set<Integer>> segments(int values, java.util.function.IntUnaryOperator digit) {
		List<Set<Integer>> out = new ArrayList<>();
		for (char segment : "abcdefg".toCharArray()) {
			out.add(where(values, v -> SEGMENTS[digit.applyAsInt(v)].indexOf(segment) >= 0));
		}
		return out;
	}
}
