package io.palmisano.aibuilder.core.gadgets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.palmisano.aibuilder.core.blocks.BlockEntityData;
import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;

/**
 * A row of levers (spec v2 §5.3), one a bit, 4 apart: each on a stone post with a sign on its front saying the row and
 * what the bit is worth (A, 4), a buffer repeater behind it, and its output port behind that, at gy 1. Pure.
 */
final class LeverRow {
	private LeverRow() {
	}

	static Gadget make(int bits, String label) {
		List<LocalBlock> blocks = new ArrayList<>();
		List<Port> ports = new ArrayList<>();
		Map<String, Pos> marks = new LinkedHashMap<>();
		for (int i = 0; i < bits; i++) {
			int x = 4 * i;
			String worth = String.valueOf(1 << i);
			blocks.add(new LocalBlock(new Pos(x, 0, 0), new BlockSpec("oak_wall_sign", Map.of("facing", "back")), null,
					new BlockEntityData.Sign(List.of(label.toUpperCase(Locale.ROOT), worth, "", ""), List.of("", "", "", ""), "black", false)));
			blocks.add(new LocalBlock(new Pos(x, 0, 1), BlockSpec.of("stone")));
			blocks.add(new LocalBlock(new Pos(x, 1, 1), new BlockSpec("lever", Map.of("face", "floor", "facing", "forward"))));
			blocks.add(new LocalBlock(new Pos(x, 0, 2), BlockSpec.of("stone")));
			blocks.add(new LocalBlock(new Pos(x, 1, 2), Pla.repeater("forward")));
			ports.add(new Port(label + i, false, new Pos(x, 1, 3), "lever " + label + i + " (worth " + worth + ")"));
			marks.put("lever " + label + i, new Pos(x, 1, 1));
		}
		return new Gadget("lever_row", new Pos(4 * (bits - 1) + 1, 2, 3), blocks, ports, marks, 4);
	}
}
