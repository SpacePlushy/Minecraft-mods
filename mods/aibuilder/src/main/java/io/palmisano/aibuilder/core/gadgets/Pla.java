package io.palmisano.aibuilder.core.gadgets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;

/**
 * A programmable logic array (spec v2 §5.3, the M9 plan's design calls): any functions of up to 4 inputs, wired the same
 * proven way every time. Layers by gy: 0 output supports, 1 output dust, 2 product supports and OR taps, 3 product dust,
 * 4 input supports and AND taps, 5 input dust. Input i enters at gy 5 through the front, into a true line along gz at
 * gx 4i and, through an inverter, its complement at 4i+2. Product t is a dust row along gx at gz {@code C0 + 2t}; an AND
 * tap is a wall torch beside a line's support right over the row, off while the line is on, so the row carries
 * NOT(the AND of the lines it taps). An OR tap is a wall torch on a row's support right over an output line, on while
 * the product holds. Output lines run along gz under the AND plane, 2 apart, and leave through a buffer repeater at
 * gy 1, at the back or the front. Air above every torch (a torch strongly powers the block over it); repeaters only where
 * no tap needs the dust; with at most 4 inputs every OR tap is within 14 of every AND tap, so the rows need none. Pure.
 */
public final class Pla {
	/**
	 * How a logic array is laid out: its kind, its inputs' and outputs' names, the gx each output line runs at (even,
	 * distinct), and which outputs (by index) leave through the front instead of the back.
	 */
	public record Layout(String kind, List<String> inputs, List<String> outputs, List<Integer> outputSlots, Set<Integer> frontOutputs) {
		public Layout {
			inputs = List.copyOf(inputs);
			outputs = List.copyOf(outputs);
			outputSlots = List.copyOf(outputSlots);
			frontOutputs = Set.copyOf(frontOutputs);
		}
	}

	/** The gz of the first product row: the input stage (buffer, branch, inverter) is in front of it. */
	static final int C0 = 5;
	/** Game ticks a redstone tick takes: a torch's or a delay-1 repeater's step. */
	private static final int STEP = 2;
	private static final BlockSpec STONE = BlockSpec.of("stone");
	private static final BlockSpec DUST = BlockSpec.of("redstone_wire");

	private Pla() {
	}

	/** The array for {@code on} (output j's on-set, bit i of an input value being input i), {@code dontCare} either way. */
	public static Gadget build(List<Set<Integer>> on, Set<Integer> dontCare, Layout layout) {
		int n = layout.inputs().size();
		int m = layout.outputs().size();
		if (n < 1 || n > 4 || m < 1 || on.size() != m || layout.outputSlots().size() != m) {
			throw new IllegalArgumentException("a logic array takes 1 to 4 inputs and an on-set and a slot for each output");
		}
		Set<Integer> slots = new HashSet<>();
		for (int slot : layout.outputSlots()) {
			if (slot < 0 || slot % 2 != 0 || !slots.add(slot)) {
				throw new IllegalArgumentException("output slots must be even, distinct and not negative: " + layout.outputSlots());
			}
		}
		List<Logic.Product> products = Logic.minimize(n, on, dontCare);
		int rows = products.size();
		int width = Math.max(4 * n, layout.outputSlots().stream().mapToInt(Integer::intValue).max().orElse(0) + 1);
		int depth = C0 + 2 * rows + 1;
		int lineEnd = C0 + 2 * Math.max(0, rows - 1);
		Map<Pos, BlockSpec> blocks = new LinkedHashMap<>();
		int longestIn = 0;

		for (int i = 0; i < n; i++) {
			int line = 4 * i;
			put(blocks, line, 4, 0, STONE);
			put(blocks, line, 5, 0, repeater("forward"));
			longestIn = Math.max(longestIn, lineAlongZ(blocks, line, 1, lineEnd, z -> z < C0 || (z - C0) % 2 == 1));
			// The complement: a branch into a block, a torch on its far side, off while the line is on.
			put(blocks, line + 1, 4, 2, STONE);
			put(blocks, line + 1, 5, 2, DUST);
			put(blocks, line + 2, 5, 2, STONE);
			put(blocks, line + 2, 5, 3, torch("forward"));
			longestIn = Math.max(longestIn, lineAlongZ(blocks, line + 2, 4, lineEnd, z -> z < C0 || (z - C0) % 2 == 1) + 1);
		}

		Map<Integer, List<Integer>> torchesOver = new LinkedHashMap<>();
		for (int j = 0; j < m; j++) {
			torchesOver.put(j, new ArrayList<>());
		}
		for (int t = 0; t < rows; t++) {
			int z = C0 + 2 * t;
			for (int x = 0; x < width; x++) {
				put(blocks, x, 2, z, STONE);
				put(blocks, x, 3, z, DUST);
			}
			Logic.Product product = products.get(t);
			for (int i = 0; i < n; i++) {
				if (product.uses(i)) {
					// The torch is off while its line is on: tap the true line for a literal that wants the input on.
					int line = 4 * i + (product.wants(i) ? 0 : 2);
					put(blocks, line + 1, 4, z, torch("right"));
				}
			}
			for (int j : product.outputs()) {
				put(blocks, layout.outputSlots().get(j), 2, z + 1, torch("forward"));
				torchesOver.get(j).add(z + 1);
			}
		}

		List<Port> ports = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			ports.add(new Port(layout.inputs().get(i), true, new Pos(4 * i, 5, -1), "in"));
		}
		int longestOut = 0;
		for (int j = 0; j < m; j++) {
			int x = layout.outputSlots().get(j);
			List<Integer> torches = torchesOver.get(j);
			boolean front = layout.frontOutputs().contains(j);
			List<Integer> run = new ArrayList<>();
			if (front) {
				int from = torches.isEmpty() ? C0 + 1 : torches.stream().mapToInt(Integer::intValue).max().getAsInt();
				for (int z = from; z >= 1; z--) {
					run.add(z);
				}
			} else {
				int from = torches.isEmpty() ? C0 + 1 : torches.stream().mapToInt(Integer::intValue).min().getAsInt();
				for (int z = from; z <= depth - 2; z++) {
					run.add(z);
				}
			}
			String flow = front ? "back" : "forward";
			Set<Integer> repeaters = repeaters(run, Set.copyOf(torches), z -> z < C0 || (z - C0) % 2 == 0);
			for (int z : run) {
				put(blocks, x, 0, z, STONE);
				put(blocks, x, 1, z, repeaters.contains(z) ? repeater(flow) : DUST);
			}
			int buffer = front ? 0 : depth - 1;
			put(blocks, x, 0, buffer, STONE);
			put(blocks, x, 1, buffer, repeater(flow));
			ports.add(new Port(layout.outputs().get(j), false, new Pos(x, 1, front ? -1 : depth), "out"));
			longestOut = Math.max(longestOut, repeaters.size());
		}

		List<LocalBlock> out = new ArrayList<>();
		blocks.forEach((pos, spec) -> out.add(new LocalBlock(pos, spec)));
		// In: the buffer, the inverter, the line's repeaters; then the AND and OR torches; out: the line's repeaters and the buffer.
		int settle = STEP * (1 + longestIn + 2 + longestOut + 1) + 2 * STEP;
		return new Gadget(layout.kind(), new Pos(width, 6, depth), out, ports, Map.of(), settle);
	}

	/** Dust along gz at ({@code x}, 5) from {@code from} to {@code to}, on stone, with repeaters where {@code allowed}; how many. */
	private static int lineAlongZ(Map<Pos, BlockSpec> blocks, int x, int from, int to, IntPredicate allowed) {
		List<Integer> run = new ArrayList<>();
		for (int z = from; z <= to; z++) {
			run.add(z);
		}
		Set<Integer> repeaters = repeaters(run, Set.of(), allowed);
		for (int z : run) {
			put(blocks, x, 4, z, STONE);
			put(blocks, x, 5, z, repeaters.contains(z) ? repeater("forward") : DUST);
		}
		return repeaters.size();
	}

	/**
	 * Where a line needs repeaters, its spots in the order the signal flows: the first spot gets full strength, as does
	 * one under a torch ({@code full}); dust loses one a block; a repeater goes where {@code allowed} once the signal is
	 * down to 4, never on the first spot or under a torch, and gives the next spot full strength again.
	 */
	static Set<Integer> repeaters(List<Integer> run, Set<Integer> full, IntPredicate allowed) {
		Set<Integer> out = new HashSet<>();
		int strength = 15;
		for (int k = 0; k < run.size(); k++) {
			int z = run.get(k);
			if (full.contains(z)) {
				strength = 15;
			}
			if (k > 0 && strength <= 4 && allowed.test(z) && !full.contains(z) && k + 1 < run.size()) {
				out.add(z);
				strength = 15;
			} else {
				strength--;
			}
		}
		return out;
	}

	private static void put(Map<Pos, BlockSpec> blocks, int x, int y, int z, BlockSpec spec) {
		BlockSpec was = blocks.put(new Pos(x, y, z), spec);
		if (was != null && !was.equals(spec)) {
			throw new IllegalStateException("two blocks at (" + x + ", " + y + ", " + z + "): " + was + " and " + spec);
		}
	}

	/** A repeater carrying the signal toward {@code signalGoes}: its facing names its input side, the opposite one. */
	static BlockSpec repeater(String signalGoes) {
		return new BlockSpec("repeater", Map.of("facing", opposite(signalGoes), "delay", "1"));
	}

	/** A wall torch pointing {@code away} from the block it's on. */
	static BlockSpec torch(String away) {
		return new BlockSpec("redstone_wall_torch", Map.of("facing", away));
	}

	static String opposite(String direction) {
		return switch (direction) {
			case "forward" -> "back";
			case "back" -> "forward";
			case "right" -> "left";
			case "left" -> "right";
			case "up" -> "down";
			case "down" -> "up";
			default -> throw new IllegalArgumentException(direction);
		};
	}
}
