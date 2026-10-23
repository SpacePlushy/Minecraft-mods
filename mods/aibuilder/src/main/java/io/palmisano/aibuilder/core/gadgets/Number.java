package io.palmisano.aibuilder.core.gadgets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;
import io.palmisano.aibuilder.core.wire.WirePath;

/**
 * A binary number n0… shown in decimal (spec v2 §5.3, the M9 plan), 0 to 31, its inputs in a row along its front at
 * gy 5. Up to 3 bits it's one digit. From 4, two faces side by side: the tens array, its face on its right, takes n1…
 * (w = n / 2), lights the tens (w / 5, dark for 0) and works out w mod 5, which leaves its front; the ones array, its face
 * on its left, takes n0 and w mod 5 and lights 2 (w mod 5) + n0. In the zone in front of the arrays, the inputs' lines
 * run straight back at gy 5, w mod 5 turns round under them at gy 1 into the ones array (the lines nested, so none
 * cross), and n0 goes over the inputs' lines at gy 7 to the ones array. Pure.
 */
final class Number {
	/** How deep the zone in front of the arrays is. */
	private static final int FRONT = 14;
	/** The digits the tens face shows, 0 to 3: dark for 0. */
	private static final String[] TENS = {"", "bc", "abdeg", "abcdg"};
	/**
	 * How w mod 5 goes to the ones array, three bits r0 to r2 for each of 0 to 4: of the 6,720 ways, one that takes the
	 * fewest products (9 for w mod 5, 9 for the ones; 0 to 4 in binary take 11 and 9).
	 */
	private static final int[] CODE = {1, 7, 2, 0, 3};
	private static final String SEGMENTS = "abcdefg";
	private static final BlockSpec STONE = BlockSpec.of("stone");

	/** An inner wire: its spots from the first dust to the last, and the two buffers its ends join. */
	record Wire(List<Pos> spots, Set<Pos> ends) {
	}

	private record Built(Gadget gadget, List<Wire> wires) {
	}

	private Number() {
	}

	static Gadget make(int bits) {
		return build(bits).gadget();
	}

	static List<Wire> wires(int bits) {
		return build(bits).wires();
	}

	/** The tens array's outputs over w (its {@code inputs} bits): the segments a to g of w / 5, then the bits r0 to r2 of w mod 5's code. */
	static List<Set<Integer>> tensOn(int inputs) {
		List<Set<Integer>> on = new ArrayList<>();
		for (char segment : SEGMENTS.toCharArray()) {
			Set<Integer> lit = new HashSet<>();
			for (int w = 0; w < 1 << inputs; w++) {
				if (TENS[w / 5].indexOf(segment) >= 0) {
					lit.add(w);
				}
			}
			on.add(lit);
		}
		for (int bit = 0; bit < 3; bit++) {
			Set<Integer> set = new HashSet<>();
			for (int w = 0; w < 1 << inputs; w++) {
				if ((CODE[w % 5] >> bit & 1) == 1) {
					set.add(w);
				}
			}
			on.add(set);
		}
		return on;
	}

	/** The ones array's segments over its inputs n0, r2, r1, r0 (w mod 5's code): the digit 2 (w mod 5) + n0. */
	static List<Set<Integer>> onesOn() {
		List<Set<Integer>> on = new ArrayList<>();
		for (char segment : SEGMENTS.toCharArray()) {
			Set<Integer> lit = new HashSet<>();
			for (int u = 0; u < 16; u++) {
				int q = remainder(u);
				if (q >= 0 && Digit.LIT[2 * q + (u & 1)].indexOf(segment) >= 0) {
					lit.add(u);
				}
			}
			on.add(lit);
		}
		return on;
	}

	/** The ones array's inputs that never come: codes no remainder has. */
	static Set<Integer> onesDontCare() {
		Set<Integer> out = new HashSet<>();
		for (int u = 0; u < 16; u++) {
			if (remainder(u) < 0) {
				out.add(u);
			}
		}
		return out;
	}

	/** w mod 5 from the ones array's input value (its inputs 1, 2 and 3 are r2, r1 and r0), -1 for a code none has. */
	private static int remainder(int u) {
		int code = (u >> 1 & 1) << 2 | (u >> 2 & 1) << 1 | (u >> 3 & 1);
		for (int q = 0; q < CODE.length; q++) {
			if (CODE[q] == code) {
				return q;
			}
		}
		return -1;
	}

	private static Built build(int bits) {
		if (bits <= 3) {
			List<Set<Integer>> on = new ArrayList<>();
			for (char segment : SEGMENTS.toCharArray()) {
				Set<Integer> lit = new HashSet<>();
				for (int v = 0; v < 1 << bits; v++) {
					if (Digit.LIT[v].indexOf(segment) >= 0) {
						lit.add(v);
					}
				}
				on.add(lit);
			}
			Digit.Built one = Digit.build(new Digit.Spec("number_ones", names(0, bits), on, Set.of(), List.of(), List.of(), false, "ones "));
			Gadget g = one.gadget();
			List<Port> ports = g.ports().stream().map(p -> input(p.name(), p.at())).toList();
			return new Built(new Gadget("number", g.size(), g.blocks(), ports, g.marks(), g.settleTicks()), List.of());
		}
		int w = bits - 1;
		List<Set<Integer>> tensOn = tensOn(w);
		Digit.Built tens = Digit.build(new Digit.Spec("number_tens", names(1, w), tensOn.subList(0, 7), Set.of(), List.of("r0", "r1", "r2"),
				tensOn.subList(7, 10), true, "tens "));
		Digit.Built ones = Digit.build(new Digit.Spec("number_ones", List.of("n0", "r2", "r1", "r0"), onesOn(), onesDontCare(), List.of(), List.of(),
				false, "ones "));
		int tensX = 2;
		int onesX = tensX + tens.gadget().size().x() + 1;
		Map<Pos, BlockSpec> blocks = new LinkedHashMap<>();
		Map<String, Pos> marks = new LinkedHashMap<>();
		for (LocalBlock b : tens.gadget().blocks()) {
			put(blocks, new Pos(b.pos().x() + tensX, b.pos().y(), b.pos().z() + FRONT), b.block());
		}
		for (LocalBlock b : ones.gadget().blocks()) {
			put(blocks, new Pos(b.pos().x() + onesX, b.pos().y(), b.pos().z() + FRONT), b.block());
		}
		tens.gadget().marks().forEach((name, p) -> marks.put(name, new Pos(p.x() + tensX, p.y(), p.z() + FRONT)));
		ones.gadget().marks().forEach((name, p) -> marks.put(name, new Pos(p.x() + onesX, p.y(), p.z() + FRONT)));

		// The number's inputs: n0 on the left, then n1… in line with the tens array's; each a buffer in, then its line.
		List<Port> ports = new ArrayList<>();
		List<Wire> wires = new ArrayList<>();
		Map<String, Pos> tensIn = new LinkedHashMap<>();
		tens.gadget().ports().forEach(p -> tensIn.put(p.name(), new Pos(p.at().x() + tensX, p.at().y(), p.at().z() + FRONT)));
		Map<String, Pos> onesIn = new LinkedHashMap<>();
		ones.gadget().ports().forEach(p -> onesIn.put(p.name(), new Pos(p.at().x() + onesX, p.at().y(), p.at().z() + FRONT)));
		for (int i = 0; i < bits; i++) {
			int x = i == 0 ? 0 : tensIn.get("n" + i).x();
			ports.add(input("n" + i, new Pos(x, 5, -1)));
			put(blocks, new Pos(x, 4, 0), STONE);
			put(blocks, new Pos(x, 5, 0), Pla.repeater("forward"));
		}
		for (int i = 1; i < bits; i++) {
			Pos to = tensIn.get("n" + i);
			wires.add(lay(blocks, List.of(new Pos(to.x(), 5, 1), to)));
		}
		Pos d0 = onesIn.get("n0");
		wires.add(lay(blocks, List.of(new Pos(0, 5, 1), new Pos(0, 7, 3), new Pos(d0.x(), 7, 3), new Pos(d0.x(), 7, FRONT - 3), d0)));
		// w mod 5's code, nested: the bit nearest the faces turns first, into the ones array's input nearest them.
		String[] nest = {"r2", "r1", "r0"};
		for (int k = 0; k < nest.length; k++) {
			Pos from = tensIn.get(nest[k]);
			Pos to = onesIn.get(nest[k]);
			int row = FRONT - 5 - 2 * k;
			wires.add(lay(blocks, List.of(from, new Pos(from.x(), 1, row), new Pos(to.x(), 1, row), to)));
		}

		List<LocalBlock> out = new ArrayList<>();
		blocks.forEach((pos, block) -> out.add(new LocalBlock(pos, block)));
		int depth = FRONT + Math.max(tens.gadget().size().z(), ones.gadget().size().z());
		int height = Math.max(tens.gadget().size().y(), ones.gadget().size().y());
		// The longest way through: an input's buffer, the tens array to w mod 5, its wire, the ones array to its face.
		int qWire = 0;
		for (Wire wire : wires) {
			qWire = Math.max(qWire, WirePath.repeaters(wire.spots()).size());
		}
		int settle = 2 + Math.max(tens.gadget().settleTicks(), tens.decoder().settleTicks() + 2 * qWire + ones.gadget().settleTicks()) + 4;
		Gadget gadget = new Gadget("number", new Pos(onesX + ones.gadget().size().x(), height, depth), out, ports, marks, settle);
		return new Built(gadget, wires);
	}

	/** Dust (and repeaters) along the way through {@code points}, stone under each spot that has nothing under it. */
	private static Wire lay(Map<Pos, BlockSpec> blocks, List<Pos> points) {
		List<Pos> spots = WirePath.trace(points);
		Set<Integer> repeaters = WirePath.repeaters(spots);
		Set<Pos> supports = new HashSet<>();
		for (Pos s : spots) {
			Pos under = new Pos(s.x(), s.y() - 1, s.z());
			if (!blocks.containsKey(under)) {
				supports.add(under);
			}
		}
		for (LocalBlock b : WirePath.blocks(spots, repeaters, supports)) {
			put(blocks, b.pos(), b.block());
		}
		Pos first = spots.getFirst();
		Pos last = spots.getLast();
		return new Wire(spots, Set.of(behind(first, spots.get(1)), ahead(last, spots.get(spots.size() - 2))));
	}

	/** The buffer a wire's first spot is fed from: the spot before it, back along the way it leaves. */
	private static Pos behind(Pos first, Pos next) {
		int dx = Integer.signum(next.x() - first.x());
		int dz = Integer.signum(next.z() - first.z());
		return new Pos(first.x() - dx, first.y(), first.z() - dz);
	}

	/** The buffer a wire's last spot feeds: the spot past it, on along the way it arrives. */
	private static Pos ahead(Pos last, Pos before) {
		int dx = Integer.signum(last.x() - before.x());
		int dz = Integer.signum(last.z() - before.z());
		return new Pos(last.x() + dx, last.y(), last.z() + dz);
	}

	private static List<String> names(int from, int count) {
		List<String> out = new ArrayList<>();
		for (int i = from; i < from + count; i++) {
			out.add("n" + i);
		}
		return out;
	}

	private static Port input(String name, Pos at) {
		int bit = Integer.parseInt(name.substring(1));
		return new Port(name, true, at, "bit " + bit + " of the number (worth " + (1 << bit) + ")");
	}

	private static void put(Map<Pos, BlockSpec> blocks, Pos pos, BlockSpec spec) {
		BlockSpec was = blocks.put(pos, spec);
		if (was != null && !was.equals(spec)) {
			throw new IllegalStateException("two blocks at " + pos + ": " + was + " and " + spec);
		}
	}
}
