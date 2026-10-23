package io.palmisano.aibuilder.core.gadgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;

/**
 * A toggle (spec v2 §5.3): a waxed copper bulb flips each time {@code in} turns on, and a comparator reading it gives
 * {@code out}. Waxed, so it never weathers. Pure.
 */
final class Toggle {
	static final BlockSpec BULB = BlockSpec.of("waxed_copper_bulb");

	private Toggle() {
	}

	static Gadget make() {
		List<LocalBlock> blocks = new ArrayList<>();
		Pla.stand(blocks, new Pos(0, 1, 0), Pla.repeater("forward"));
		Pla.stand(blocks, new Pos(0, 1, 1), BULB);
		Pla.stand(blocks, new Pos(0, 1, 2), new BlockSpec("comparator", Map.of("facing", "back")));
		Pla.stand(blocks, new Pos(0, 1, 3), Pla.repeater("forward"));
		List<Port> ports = List.of(new Port("in", true, new Pos(0, 1, -1), "each pulse flips out"),
				new Port("out", false, new Pos(0, 1, 4), "on or off, flipped by each pulse at in"));
		return new Gadget("toggle", new Pos(1, 2, 4), blocks, ports, Map.of("bulb", new Pos(0, 1, 1)), 10);
	}
}
