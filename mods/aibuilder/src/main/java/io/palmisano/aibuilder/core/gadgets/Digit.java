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

/**
 * A seven-segment digit (spec v2 §5.3, the M9 plan): a 4-bit BCD code d0…d3 in at gy 5 on the front, shown on a 5 × 5
 * face of lamps at the front-left, gy 7 to 11, facing front. A decoder logic array sits to the right of the face; its
 * seven segment lines leave its back, climb, turn left and come forward beside it into the face, each into a repeater
 * behind its segment's centre lamp (a lamp a repeater powers lights the lamps beside it too, so a segment is the centre
 * and its ends). The lines turn in an order that never crosses: one turning later has a larger slot and a column no
 * further right, and lines that share a column are 2 levels apart. Codes 10 to 15 show no digit. Pure.
 */
final class Digit {
	/** The segments each digit lights, 0 to 9. */
	static final String[] LIT = {"abcdef", "bc", "abdeg", "abcdg", "bcfg", "acdfg", "acdefg", "abc", "abcdefg", "abcdfg"};
	private static final String SEGMENTS = "abcdefg";
	/** Each segment's centre lamp on the face: its column (gx) and row (0 at the bottom). */
	private static final Map<Character, int[]> CENTRE = Map.of('a', new int[] {2, 4}, 'b', new int[] {4, 3}, 'c', new int[] {4, 1}, 'd',
			new int[] {2, 0}, 'e', new int[] {0, 1}, 'f', new int[] {0, 3}, 'g', new int[] {2, 2});
	/** The face cells that aren't lamps: between the segments, so a centre lights only its own segment's ends. */
	private static final Set<String> HOLES = Set.of("1,3", "2,3", "3,3", "1,1", "2,1", "3,1");
	/** The gy of the face's bottom row. */
	private static final int FACE_Y = 7;
	/** Where the decoder starts (gx): the face, its returns and a block of room to its left. */
	private static final int DECODER_X = 6;
	/** The order the lines turn behind the decoder, and so their slots there, 2 apart from 0. */
	private static final String TURNS = "cbdgaef";
	private static final BlockSpec STONE = BlockSpec.of("stone");

	private Digit() {
	}

	static Gadget make() {
		return build().gadget;
	}

	/** Each segment's on-set over the 4-bit code, a to g. */
	static List<Set<Integer>> onSets() {
		List<Set<Integer>> on = new ArrayList<>();
		for (char segment : SEGMENTS.toCharArray()) {
			Set<Integer> lit = new HashSet<>();
			for (int v = 0; v < 10; v++) {
				if (LIT[v].indexOf(segment) >= 0) {
					lit.add(v);
				}
			}
			on.add(lit);
		}
		return on;
	}

	/** The seven lines from the decoder to the face, each spot in order. */
	static List<List<Pos>> paths() {
		return build().paths;
	}

	private record Built(Gadget gadget, List<List<Pos>> paths) {
	}

	private static Built build() {
		Set<Integer> dontCare = Set.of(10, 11, 12, 13, 14, 15);
		List<Integer> slots = new ArrayList<>();
		for (char segment : SEGMENTS.toCharArray()) {
			slots.add(2 * TURNS.indexOf(segment));
		}
		Gadget decoder = Pla.build(onSets(), dontCare, new Pla.Layout("digit_decoder", List.of("d0", "d1", "d2", "d3"),
				List.of("a", "b", "c", "d", "e", "f", "g"), slots, Set.of()));
		Map<Pos, BlockSpec> blocks = new LinkedHashMap<>();
		for (LocalBlock b : decoder.blocks()) {
			put(blocks, new Pos(b.pos().x() + DECODER_X, b.pos().y(), b.pos().z()), b.block());
		}
		int back = decoder.size().z();

		// The face, and the repeater behind each segment's centre lamp.
		Map<String, Pos> marks = new LinkedHashMap<>();
		for (int column = 0; column < 5; column++) {
			for (int row = 0; row < 5; row++) {
				boolean hole = HOLES.contains(column + "," + row);
				put(blocks, new Pos(column, FACE_Y + row, 0), BlockSpec.of(hole ? "black_concrete" : "redstone_lamp"));
			}
		}
		for (char segment : SEGMENTS.toCharArray()) {
			int[] centre = CENTRE.get(segment);
			Pos lamp = new Pos(centre[0], FACE_Y + centre[1], 0);
			marks.put("segment " + segment, lamp);
			put(blocks, new Pos(lamp.x(), lamp.y(), 1), Pla.repeater("back"));
			put(blocks, new Pos(lamp.x(), lamp.y() - 1, 1), STONE);
		}

		// The lines: from the decoder's back port spot, up a level a block, on to its row, left to its column, forward to the face.
		List<List<Pos>> paths = new ArrayList<>();
		int longest = 0;
		for (int k = 0; k < TURNS.length(); k++) {
			char segment = TURNS.charAt(k);
			int[] centre = CENTRE.get(segment);
			int x = DECODER_X + 2 * k;
			int height = FACE_Y + centre[1];
			int row = back + 7 + 2 * k;
			List<Pos> path = new ArrayList<>();
			for (int step = 0; step < height; step++) {
				path.add(new Pos(x, 1 + step, back + step));
			}
			if (back + height - 1 > row) {
				throw new IllegalStateException("segment " + segment + " climbs past its row");
			}
			for (int z = back + height; z <= row; z++) {
				path.add(new Pos(x, height, z));
			}
			for (int across = x - 1; across >= centre[0]; across--) {
				path.add(new Pos(across, height, row));
			}
			for (int z = row - 1; z >= 2; z--) {
				path.add(new Pos(centre[0], height, z));
			}
			paths.add(path);
			longest = Math.max(longest, lay(blocks, path));
		}

		List<LocalBlock> out = new ArrayList<>();
		blocks.forEach((pos, spec) -> out.add(new LocalBlock(pos, spec)));
		List<Port> ports = new ArrayList<>();
		for (Port in : decoder.ports()) {
			if (in.input()) {
				ports.add(new Port(in.name(), true, new Pos(in.at().x() + DECODER_X, in.at().y(), in.at().z()), "bit " + in.name().substring(1)
						+ " of the digit's code"));
			}
		}
		int depth = back + 7 + 2 * (TURNS.length() - 1) + 1;
		// The decoder, then the line's repeaters, then the repeater behind the lamp.
		int settle = decoder.settleTicks() + 2 * longest + 2 + 4;
		Gadget gadget = new Gadget("digit", new Pos(DECODER_X + decoder.size().x(), FACE_Y + 5, depth), out, ports, marks, settle);
		return new Built(gadget, paths);
	}

	/** Dust along {@code path} on stone, repeaters on straight flat stretches where the signal's down to 4; how many repeaters. */
	private static int lay(Map<Pos, BlockSpec> blocks, List<Pos> path) {
		int strength = 15;
		int repeaters = 0;
		for (int k = 0; k < path.size(); k++) {
			Pos p = path.get(k);
			boolean straight = k > 0 && k + 1 < path.size() && flatAndInLine(path.get(k - 1), p, path.get(k + 1));
			BlockSpec spec = BlockSpec.of("redstone_wire");
			if (straight && strength <= 4) {
				spec = Pla.repeater(direction(p, path.get(k + 1)));
				strength = 15;
				repeaters++;
			} else {
				strength--;
			}
			put(blocks, p, spec);
			put(blocks, new Pos(p.x(), p.y() - 1, p.z()), STONE);
		}
		return repeaters;
	}

	private static boolean flatAndInLine(Pos a, Pos b, Pos c) {
		return a.y() == b.y() && b.y() == c.y() && (a.x() == c.x() || a.z() == c.z()) && Math.abs(a.x() - c.x()) + Math.abs(a.z() - c.z()) == 2;
	}

	/** The direction word from {@code a} to the next spot {@code b} on the level. */
	private static String direction(Pos a, Pos b) {
		if (b.z() > a.z()) {
			return "forward";
		}
		if (b.z() < a.z()) {
			return "back";
		}
		return b.x() > a.x() ? "right" : "left";
	}

	private static void put(Map<Pos, BlockSpec> blocks, Pos pos, BlockSpec spec) {
		BlockSpec was = blocks.put(pos, spec);
		if (was != null && !was.equals(spec)) {
			throw new IllegalStateException("two blocks at " + pos + ": " + was + " and " + spec);
		}
	}
}
