package io.palmisano.aibuilder.core.gadgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;

/**
 * A ripple counter (spec v2 §5.3), a stage every 5 blocks: a waxed copper bulb read by a comparator gives q. Bulb 0
 * flips on each pulse at {@code count}; each later bulb is fed the inverse of the stage before (a torch on a block that
 * stage's q powers), so it flips as that stage goes from 1 to 0, and the bits count in binary, round to 0. Pure.
 */
final class Counter {
	private Counter() {
	}

	static Gadget make(int bits) {
		List<LocalBlock> blocks = new ArrayList<>();
		List<Port> ports = new ArrayList<>();
		Pla.stand(blocks, new Pos(0, 1, 0), Pla.repeater("forward"));
		ports.add(new Port("count", true, new Pos(0, 1, -1), "each pulse adds one"));
		for (int i = 0; i < bits; i++) {
			int x = 5 * i;
			// A later bulb is fed while the stage before reads 0, as it does at the start: placed powered, starting it up
			// doesn't flip it. The inverter's line is placed as it will be, on, for the same reason.
			Pla.stand(blocks, new Pos(x, 1, 1), i == 0 ? Toggle.BULB : new BlockSpec("waxed_copper_bulb", Map.of("powered", "true")));
			Pla.stand(blocks, new Pos(x, 1, 2), new BlockSpec("comparator", Map.of("facing", "back")));
			Pla.stand(blocks, new Pos(x, 1, 3), Pla.DUST);
			Pla.stand(blocks, new Pos(x, 1, 4), Pla.repeater("forward"));
			ports.add(new Port("q" + i, false, new Pos(x, 1, 5), "bit " + i + " of the count (worth " + (1 << i) + ")"));
			if (i + 1 < bits) {
				Pla.stand(blocks, new Pos(x + 1, 1, 3), Pla.DUST);
				blocks.add(new LocalBlock(new Pos(x + 2, 1, 3), Pla.STONE));
				blocks.add(new LocalBlock(new Pos(x + 2, 1, 2), Pla.torch("back")));
				Pla.stand(blocks, new Pos(x + 2, 1, 1), new BlockSpec("redstone_wire", Map.of("power", "15")));
				Pla.stand(blocks, new Pos(x + 3, 1, 1), new BlockSpec("redstone_wire", Map.of("power", "14")));
				Pla.stand(blocks, new Pos(x + 4, 1, 1), new BlockSpec("repeater", Map.of("facing", "left", "delay", "1", "powered", "true")));
			}
		}
		return new Gadget("counter", new Pos(5 * (bits - 1) + 1, 2, 5), blocks, ports, Map.of(), 6 * bits + 6);
	}
}
