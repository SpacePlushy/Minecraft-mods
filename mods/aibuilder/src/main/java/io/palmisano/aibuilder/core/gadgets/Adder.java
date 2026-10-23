package io.palmisano.aibuilder.core.gadgets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;

/**
 * A ripple-carry adder (spec v2 §5.3): a full-adder logic array a bit, side by side {@value #PITCH} apart, each with
 * inputs c, a and b. A cell's carry leaves through its front and hops up a dust staircase, in the row in front of the
 * cells, into the next cell's c; the last cell's carry leaves through the back as the top bit of the sum. Inputs a0…,
 * b0… and the carry in c0 enter at gy 5, a block out from the cells, through a buffer repeater into each cell's own, as
 * every port has one (a wire may arrive at strength 1); the sum s0…s{bits} leaves at the back, gy 1. Pure.
 */
final class Adder {
	static final int PITCH = 14;

	private Adder() {
	}

	static Gadget make(int bits) {
		Set<Integer> sum = new HashSet<>();
		Set<Integer> carry = new HashSet<>();
		for (int v = 0; v < 8; v++) {
			if (Integer.bitCount(v) % 2 == 1) {
				sum.add(v);
			}
			if (Integer.bitCount(v) >= 2) {
				carry.add(v);
			}
		}
		List<Set<Integer>> on = List.of(sum, carry);
		Gadget cell = Pla.build(on, Set.of(), new Pla.Layout("adder_cell", List.of("c", "a", "b"), List.of("s", "cout"), List.of(2, 10), Set.of(1)));
		Gadget last = Pla.build(on, Set.of(), new Pla.Layout("adder_cell", List.of("c", "a", "b"), List.of("s", "cout"), List.of(2, 10), Set.of()));
		// The cells sit one block back: the row in front holds the carry hops and the inputs' dust.
		int depth = cell.size().z() + 1;
		List<LocalBlock> blocks = new ArrayList<>();
		List<Port> as = new ArrayList<>();
		List<Port> bs = new ArrayList<>();
		List<Port> sums = new ArrayList<>();
		for (int i = 0; i < bits; i++) {
			int x0 = PITCH * i;
			Gadget g = i == bits - 1 ? last : cell;
			for (LocalBlock b : g.blocks()) {
				blocks.add(new LocalBlock(new Pos(b.pos().x() + x0, b.pos().y(), b.pos().z() + 1), b.block()));
			}
			if (i < bits - 1) {
				// cout's port spot (x0 + 10, 1) up a step a block to the next cell's c port spot (x0 + 14, 5).
				for (int k = 0; k <= 4; k++) {
					blocks.add(new LocalBlock(new Pos(x0 + 10 + k, k, 0), BlockSpec.of("stone")));
					blocks.add(new LocalBlock(new Pos(x0 + 10 + k, k + 1, 0), BlockSpec.of("redstone_wire")));
				}
			}
			as.add(input(blocks, "a" + i, x0 + 4, "bit " + i + " of a"));
			bs.add(input(blocks, "b" + i, x0 + 8, "bit " + i + " of b"));
			sums.add(new Port("s" + i, false, new Pos(x0 + 2, 1, depth), "bit " + i + " of the sum"));
		}
		Port carryIn = input(blocks, "c0", 0, "a carry in");
		sums.add(new Port("s" + bits, false, new Pos(PITCH * (bits - 1) + 10, 1, depth), "bit " + bits + " of the sum (the carry out)"));
		List<Port> ports = new ArrayList<>(as);
		ports.addAll(bs);
		ports.add(carryIn);
		ports.addAll(sums);
		// Each cell waits on the carry from the one before; the inputs' buffers add a step.
		int settle = bits * cell.settleTicks() + 6;
		return new Gadget("adder", new Pos(PITCH * (bits - 1) + cell.size().x(), 6, depth), blocks, ports, Map.of(), settle);
	}

	/** An input at gy 5 a block in front of the cells: the port there, a buffer repeater (on stone) between it and the cell's. */
	private static Port input(List<LocalBlock> blocks, String name, int x, String what) {
		blocks.add(new LocalBlock(new Pos(x, 4, 0), BlockSpec.of("stone")));
		blocks.add(new LocalBlock(new Pos(x, 5, 0), Pla.repeater("forward")));
		return new Port(name, true, new Pos(x, 5, -1), what);
	}
}
