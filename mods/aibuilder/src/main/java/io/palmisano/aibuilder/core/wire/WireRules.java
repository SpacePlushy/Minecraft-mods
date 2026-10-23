package io.palmisano.aibuilder.core.wire;

import static io.palmisano.aibuilder.core.wire.WirePath.spot;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.ProtectionMask;
import io.palmisano.aibuilder.core.shapes.Box;

/**
 * What a wire may touch (spec v2 §5.4), so nothing of it joins or sets off anything but its two ports: its dust goes
 * only into free spots, on ground or on stone it places, with nothing redstone beside it (but the ports' buffers) or
 * beside what it stands on, no other dust a step from it, and room for its climbs. Under what it stands on is fine:
 * that's going over another wire. Pure.
 */
public final class WireRules {
	/** A wire refused: {@code code} for Claude (out_of_bounds, protected, blocked, touches_redstone, bad_argument), and where. */
	public static final class Refused extends IllegalArgumentException {
		private final String code;

		Refused(String code, String message) {
			super(message);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}

	private static final String APART = ": keep a block between them (to cross a wire, go over it: a via point 2 above)";

	private WireRules() {
	}

	/** The positions a check of {@code spots} reads: each spot, the one under it, and every one beside or a step from either. */
	public static Set<Pos> needed(List<Pos> spots) {
		Set<Pos> out = new LinkedHashSet<>();
		for (Pos s : spots) {
			Pos under = below(s);
			out.add(s);
			out.add(under);
			out.addAll(beside(s));
			out.addAll(beside(under));
			out.addAll(steps(s));
		}
		return out;
	}

	/**
	 * Where the wire on {@code spots} needs stone under its dust (over a free spot), once it's checked against what's
	 * around it ({@code cells}, any other wire laid with it included): refused whole, saying where. {@code exempt} are the
	 * two ports' buffer repeaters, which its ends join.
	 */
	public static Set<Pos> supports(List<Pos> spots, Map<Pos, Cell> cells, Set<Pos> exempt, Box allowed, ProtectionMask mask) {
		for (Pos s : spots) {
			if (!allowed.contains(s)) {
				throw new Refused("out_of_bounds", "the wire would run through " + spot(s) + ", past the allowed box");
			}
		}
		for (int i = 0; i < spots.size(); i++) {
			for (int j = i + 2; j < spots.size(); j++) {
				if (beside(spots.get(i)).contains(spots.get(j)) || steps(spots.get(i)).contains(spots.get(j))) {
					throw new Refused("bad_argument", "the wire would come back beside itself at " + spot(spots.get(i)) + " and " + spot(spots.get(j))
							+ ": choose via points that keep its runs 2 apart");
				}
			}
		}
		Set<Pos> supports = new LinkedHashSet<>();
		for (Pos s : spots) {
			if (mask.isProtected(s) || mask.isUnbreakable(s)) {
				throw new Refused("protected", "something player-made is at " + spot(s) + ", where the wire's dust would go");
			}
			Cell here = cell(cells, s);
			if (here == Cell.DUST || here == Cell.COMPONENT) {
				throw new Refused("touches_redstone", spot(s) + " is " + name(here) + ", where the wire's dust would go");
			}
			if (here != Cell.FREE) {
				throw new Refused("blocked", spot(s) + " holds a block, where the wire's dust would go");
			}
			Pos under = below(s);
			Cell ground = cell(cells, under);
			if (ground == Cell.FREE) {
				if (!allowed.contains(under)) {
					throw new Refused("out_of_bounds", "the stone under the wire at " + spot(s) + " would be at " + spot(under) + ", past the allowed box");
				}
				if (mask.isProtected(under) || mask.isUnbreakable(under)) {
					throw new Refused("protected", "something player-made is at " + spot(under) + ", where the stone under the wire would go");
				}
				supports.add(under);
			} else if (ground == Cell.DUST || ground == Cell.COMPONENT) {
				throw new Refused("touches_redstone", spot(under) + " is " + name(ground) + ", under the wire at " + spot(s) + APART);
			} else if (ground != Cell.SOLID) {
				throw new Refused("blocked", "dust can't stand on what's at " + spot(under) + ", under the wire at " + spot(s));
			}
		}
		Set<Pos> ours = new HashSet<>(spots);
		for (int k = 1; k < spots.size(); k++) {
			Pos a = spots.get(k - 1);
			Pos b = spots.get(k);
			if (a.y() != b.y()) {
				Pos over = above(a.y() < b.y() ? a : b);
				if (cell(cells, over) != Cell.FREE || supports.contains(over) || ours.contains(over)) {
					throw new Refused("blocked", "the wire " + (a.y() < b.y() ? "climbs" : "drops") + " from " + spot(a) + " to " + spot(b) + ", which needs "
							+ spot(over) + " empty");
				}
			}
		}
		for (Pos s : spots) {
			ours.add(below(s));
		}
		for (Pos s : spots) {
			for (Pos n : beside(s)) {
				Cell c = cell(cells, n);
				if (!ours.contains(n) && !exempt.contains(n) && (c == Cell.DUST || c == Cell.COMPONENT)) {
					throw new Refused("touches_redstone", spot(n) + " is " + name(c) + ", beside the wire at " + spot(s) + APART);
				}
			}
			Pos under = below(s);
			for (Pos n : beside(under)) {
				Cell c = cell(cells, n);
				if (!ours.contains(n) && !exempt.contains(n) && (c == Cell.COMPONENT || c == Cell.DUST && !n.equals(below(under)))) {
					throw new Refused("touches_redstone", spot(n) + " is " + name(c) + ", beside the " + (supports.contains(under) ? "stone" : "block")
							+ " under the wire at " + spot(s) + APART);
				}
			}
			for (Pos n : steps(s)) {
				if (!ours.contains(n) && !exempt.contains(n) && cell(cells, n) == Cell.DUST) {
					throw new Refused("touches_redstone", spot(n) + " is dust, a step from the wire at " + spot(s) + APART);
				}
			}
		}
		return supports;
	}

	/** What's at {@code p}; a spot the world couldn't say (not loaded) counts as something a wire can't touch. */
	private static Cell cell(Map<Pos, Cell> cells, Pos p) {
		return cells.getOrDefault(p, Cell.OTHER);
	}

	private static String name(Cell c) {
		return c == Cell.DUST ? "dust" : "redstone";
	}

	private static Pos below(Pos p) {
		return new Pos(p.x(), p.y() - 1, p.z());
	}

	private static Pos above(Pos p) {
		return new Pos(p.x(), p.y() + 1, p.z());
	}

	private static List<Pos> beside(Pos p) {
		return List.of(new Pos(p.x() + 1, p.y(), p.z()), new Pos(p.x() - 1, p.y(), p.z()), new Pos(p.x(), p.y() + 1, p.z()), below(p),
				new Pos(p.x(), p.y(), p.z() + 1), new Pos(p.x(), p.y(), p.z() - 1));
	}

	/** A step from {@code p}: a block sideways and a level up or down, where dust would join it. */
	private static List<Pos> steps(Pos p) {
		return List.of(new Pos(p.x() + 1, p.y() + 1, p.z()), new Pos(p.x() + 1, p.y() - 1, p.z()), new Pos(p.x() - 1, p.y() + 1, p.z()),
				new Pos(p.x() - 1, p.y() - 1, p.z()), new Pos(p.x(), p.y() + 1, p.z() + 1), new Pos(p.x(), p.y() - 1, p.z() + 1),
				new Pos(p.x(), p.y() + 1, p.z() - 1), new Pos(p.x(), p.y() - 1, p.z() - 1));
	}
}
