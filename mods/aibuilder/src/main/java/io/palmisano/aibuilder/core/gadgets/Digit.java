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
 * A seven-segment digit (spec v2 §5.3, the M9 plan): a code in at gy 5 on the front, shown on a 5 × 5 face of lamps
 * beside its decoder logic array, gy 7 to 11, facing front. The decoder's segment lines leave its back, climb, turn
 * toward the face and come forward beside the decoder into the face, each into a repeater behind its segment's centre
 * lamp (a lamp a repeater powers lights the lamps beside it too, so a segment is the centre and its ends). The lines
 * turn in an order that never crosses: one turning later has its slot further from the face and a column no nearer the
 * decoder, and lines that share a column are 2 levels apart. The plain digit takes a BCD code d0…d3, its face on the
 * left; {@link Spec} makes others (a number's two). Pure.
 */
final class Digit {
	/** The segments each digit lights, 0 to 9. */
	static final String[] LIT = {"abcdef", "bc", "abdeg", "abcdg", "bcfg", "acdfg", "acdefg", "abc", "abcdefg", "abcdfg"};
	private static final String SEGMENTS = "abcdefg";
	/** Each segment's centre lamp on the face: its column (0 at the face's left) and row (0 at the bottom). */
	private static final Map<Character, int[]> CENTRE = Map.of('a', new int[] {2, 4}, 'b', new int[] {4, 3}, 'c', new int[] {4, 1}, 'd',
			new int[] {2, 0}, 'e', new int[] {0, 1}, 'f', new int[] {0, 3}, 'g', new int[] {2, 2});
	/** The face cells that aren't lamps: between the segments, so a centre lights only its own segment's ends. */
	private static final Set<String> HOLES = Set.of("1,3", "2,3", "3,3", "1,1", "2,1", "3,1");
	/** The gy of the face's bottom row. */
	private static final int FACE_Y = 7;
	/** The highest output slot an array of 4 inputs reaches (Pla). */
	private static final int TOP_SLOT = 14;
	/** A face on the left: where the decoder starts (gx), past the face, its returns and a block of room. */
	static final int DECODER_X = 6;
	/** The order the lines turn behind the decoder to a face on its left, the first nearest it: columns 4, 2, then 0. */
	private static final String TURNS = "cbdgaef";
	/** The same to a face on its right: columns 0, 2, then 4. */
	private static final String TURNS_RIGHT = "efdgacb";
	private static final BlockSpec STONE = BlockSpec.of("stone");

	/**
	 * What a digit is made of: its decoder's inputs, each segment's on-set (a to g; one that never lights gets no line),
	 * the codes that don't matter, outputs that leave the decoder's front instead (their on-sets after), whether the face
	 * is right of the decoder, and what its marks start with ("tens " makes "tens segment a").
	 */
	record Spec(String kind, List<String> inputs, List<Set<Integer>> segments, Set<Integer> dontCare, List<String> extras,
			List<Set<Integer>> extrasOn, boolean faceRight, String marks) {
	}

	/** A digit built: the gadget, its seven (or fewer) lines' spots, and its decoder as placed in it, {@code decoderX} along. */
	record Built(Gadget gadget, List<List<Pos>> paths, Gadget decoder, int decoderX) {
	}

	private Digit() {
	}

	static Gadget make() {
		return build(bcd()).gadget();
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

	/** The seven lines from the plain digit's decoder to its face, each spot in order. */
	static List<List<Pos>> paths() {
		return build(bcd()).paths();
	}

	private static Spec bcd() {
		return new Spec("digit_decoder", List.of("d0", "d1", "d2", "d3"), onSets(), Set.of(10, 11, 12, 13, 14, 15), List.of(), List.of(), false, "");
	}

	static Built build(Spec spec) {
		List<Character> lines = new ArrayList<>();
		for (char segment : (spec.faceRight() ? TURNS_RIGHT : TURNS).toCharArray()) {
			if (!spec.segments().get(SEGMENTS.indexOf(segment)).isEmpty()) {
				lines.add(segment);
			}
		}
		int count = lines.size();
		List<String> outputs = new ArrayList<>();
		List<Set<Integer>> on = new ArrayList<>();
		List<Integer> slots = new ArrayList<>();
		List<Integer> sections = new ArrayList<>();
		Set<Integer> front = new HashSet<>();
		// Outputs that leave the front take the top slots, in a section before the segments', so they may share slots.
		int extras = spec.extras().size();
		int segmentsSection = extras > 0 ? 1 : 0;
		for (int k = 0; k < count; k++) {
			outputs.add(String.valueOf(lines.get(k)));
			on.add(spec.segments().get(SEGMENTS.indexOf(lines.get(k))));
			// The first line to turn is the one nearest the face; slots from 2, within the AND taps' reach.
			slots.add(2 + 2 * (spec.faceRight() ? count - 1 - k : k));
			sections.add(segmentsSection);
		}
		for (int e = 0; e < extras; e++) {
			front.add(outputs.size());
			outputs.add(spec.extras().get(e));
			on.add(spec.extrasOn().get(e));
			slots.add(TOP_SLOT - 2 * (extras - 1 - e));
			sections.add(0);
		}
		Gadget decoder = Pla.build(on, spec.dontCare(), new Pla.Layout(spec.kind(), spec.inputs(), outputs, slots, front, sections));
		int decoderX = spec.faceRight() ? 0 : DECODER_X;
		int faceX = spec.faceRight() ? decoder.size().x() + 1 : 0;
		Map<Pos, BlockSpec> blocks = new LinkedHashMap<>();
		for (LocalBlock b : decoder.blocks()) {
			put(blocks, new Pos(b.pos().x() + decoderX, b.pos().y(), b.pos().z()), b.block());
		}
		int back = decoder.size().z();

		// The face, and the repeater behind each centre lamp that has a line.
		Map<String, Pos> marks = new LinkedHashMap<>();
		for (int column = 0; column < 5; column++) {
			for (int row = 0; row < 5; row++) {
				boolean hole = HOLES.contains(column + "," + row);
				put(blocks, new Pos(faceX + column, FACE_Y + row, 0), BlockSpec.of(hole ? "black_concrete" : "redstone_lamp"));
			}
		}
		for (char segment : SEGMENTS.toCharArray()) {
			int[] centre = CENTRE.get(segment);
			Pos lamp = new Pos(faceX + centre[0], FACE_Y + centre[1], 0);
			marks.put(spec.marks() + "segment " + segment, lamp);
			if (lines.contains(segment)) {
				put(blocks, new Pos(lamp.x(), lamp.y(), 1), Pla.repeater("back"));
				put(blocks, new Pos(lamp.x(), lamp.y() - 1, 1), STONE);
			}
		}

		// The lines: from the decoder's back port spot, up a level a block, on to its row, across to its column, forward to the face.
		List<List<Pos>> paths = new ArrayList<>();
		int longest = 0;
		for (int k = 0; k < count; k++) {
			char segment = lines.get(k);
			int[] centre = CENTRE.get(segment);
			int x = decoderX + slots.get(k);
			int column = faceX + centre[0];
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
			int way = Integer.signum(column - x);
			for (int across = x + way; across != column + way; across += way) {
				path.add(new Pos(across, height, row));
			}
			for (int z = row - 1; z >= 2; z--) {
				path.add(new Pos(column, height, z));
			}
			paths.add(path);
			longest = Math.max(longest, lay(blocks, path));
		}

		List<LocalBlock> out = new ArrayList<>();
		blocks.forEach((pos, block) -> out.add(new LocalBlock(pos, block)));
		List<Port> ports = new ArrayList<>();
		for (Port p : decoder.ports()) {
			if (p.input() || front.contains(outputs.indexOf(p.name()))) {
				String what = p.input() ? "bit " + spec.inputs().indexOf(p.name()) + " of the digit's code" : p.what();
				ports.add(new Port(p.name(), p.input(), new Pos(p.at().x() + decoderX, p.at().y(), p.at().z()), what));
			}
		}
		int depth = back + 7 + 2 * (count - 1) + 1;
		// The decoder, then the line's repeaters, then the repeater behind the lamp.
		int settle = decoder.settleTicks() + 2 * longest + 2 + 4;
		int width = spec.faceRight() ? faceX + 5 : decoderX + decoder.size().x();
		Gadget gadget = new Gadget("digit", new Pos(width, FACE_Y + 5, depth), out, ports, marks, settle);
		return new Built(gadget, paths, decoder, decoderX);
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
