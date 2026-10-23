package io.palmisano.aibuilder.core.gadgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;

/**
 * A clock (spec v2 §5.3): a wall torch on block B feeds a loop of repeaters and dust that runs back into B, so the torch
 * flips every half period, and the loop's far corner gives {@code out}, a square wave. {@code hold} powers B and stops
 * it with out off. A flip takes the torch's redstone tick and the repeaters', 2 game ticks each, so the period is
 * 4 × (1 + the repeaters' delay): 12 at the least, as a torch turned off 8 times in 60 ticks burns out. Pure.
 */
final class Clock {
	/** The loop's repeater slots, gz 3 to 6 at gx 0; a slot the period doesn't need is dust. */
	private static final int SLOTS = 4;

	private Clock() {
	}

	static Gadget make(int period) {
		int delay = period / 4 - 1;
		int repeaters = (delay + 3) / 4;
		List<LocalBlock> blocks = new ArrayList<>();
		Pla.stand(blocks, new Pos(0, 1, 0), Pla.repeater("forward"));
		blocks.add(new LocalBlock(new Pos(0, 1, 1), Pla.STONE));
		blocks.add(new LocalBlock(new Pos(0, 1, 2), Pla.torch("forward")));
		for (int s = 0; s < SLOTS; s++) {
			BlockSpec slot = s < repeaters ? Pla.repeater("forward", delay / repeaters + (s < delay % repeaters ? 1 : 0)) : Pla.DUST;
			Pla.stand(blocks, new Pos(0, 1, 3 + s), slot);
		}
		// Across the far end, back down the right side and in to B; gx 1 stays clear between the two runs.
		for (Pos p : List.of(new Pos(0, 1, 7), new Pos(1, 1, 7), new Pos(2, 1, 7), new Pos(2, 1, 6), new Pos(2, 1, 5), new Pos(2, 1, 4),
				new Pos(2, 1, 3), new Pos(2, 1, 2), new Pos(2, 1, 1), new Pos(1, 1, 1))) {
			Pla.stand(blocks, p, Pla.DUST);
		}
		Pla.stand(blocks, new Pos(2, 1, 8), Pla.repeater("forward"));
		List<Port> ports = List.of(new Port("hold", true, new Pos(0, 1, -1), "stops the clock while on"),
				new Port("out", false, new Pos(2, 1, 9), "on and off, every " + period + " ticks"));
		return new Gadget("clock", new Pos(3, 2, 9), blocks, ports, Map.of(), period + 4);
	}
}
