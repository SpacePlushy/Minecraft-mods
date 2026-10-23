package io.palmisano.aibuilder.core.gadgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;

/**
 * A pulse maker (spec v2 §5.3): {@code in} splits at the dust past its buffer, straight on into a subtracting
 * comparator's back and round the right through a delay repeater into its side. As in turns on, the back sees it
 * {@code length} ticks before the side cancels it: one pulse that long. As it turns off, the back goes first: nothing. Pure.
 */
final class Pulse {
	private Pulse() {
	}

	static Gadget make(int length) {
		List<LocalBlock> blocks = new ArrayList<>();
		Pla.stand(blocks, new Pos(0, 1, 0), Pla.repeater("forward"));
		Pla.stand(blocks, new Pos(0, 1, 1), Pla.DUST);
		Pla.stand(blocks, new Pos(0, 1, 2), Pla.repeater("forward"));
		Pla.stand(blocks, new Pos(0, 1, 3), new BlockSpec("comparator", Map.of("facing", "back", "mode", "subtract")));
		Pla.stand(blocks, new Pos(0, 1, 4), Pla.repeater("forward"));
		Pla.stand(blocks, new Pos(1, 1, 1), Pla.DUST);
		Pla.stand(blocks, new Pos(2, 1, 1), Pla.DUST);
		Pla.stand(blocks, new Pos(2, 1, 2), Pla.repeater("forward", length / 2));
		Pla.stand(blocks, new Pos(2, 1, 3), Pla.DUST);
		Pla.stand(blocks, new Pos(1, 1, 3), Pla.repeater("left"));
		List<Port> ports = List.of(new Port("in", true, new Pos(0, 1, -1), "a pulse starts as it turns on"),
				new Port("out", false, new Pos(0, 1, 5), "a " + length + "-tick pulse each time in turns on"));
		return new Gadget("pulse", new Pos(3, 2, 5), blocks, ports, Map.of(), length + 10);
	}
}
