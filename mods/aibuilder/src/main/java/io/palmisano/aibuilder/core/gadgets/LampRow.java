package io.palmisano.aibuilder.core.gadgets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;

/** A row of lamps (spec v2 §5.3), one a bit, 4 apart: each input port at gy 1, a buffer repeater, then the lamp. Pure. */
final class LampRow {
	private LampRow() {
	}

	static Gadget make(int bits) {
		List<LocalBlock> blocks = new ArrayList<>();
		List<Port> ports = new ArrayList<>();
		Map<String, Pos> marks = new LinkedHashMap<>();
		for (int i = 0; i < bits; i++) {
			int x = 4 * i;
			blocks.add(new LocalBlock(new Pos(x, 0, 0), BlockSpec.of("stone")));
			blocks.add(new LocalBlock(new Pos(x, 1, 0), Pla.repeater("forward")));
			blocks.add(new LocalBlock(new Pos(x, 1, 1), BlockSpec.of("redstone_lamp")));
			ports.add(new Port("in" + i, true, new Pos(x, 1, -1), "lamp " + i));
			marks.put("lamp " + i, new Pos(x, 1, 1));
		}
		return new Gadget("lamp_row", new Pos(4 * (bits - 1) + 1, 2, 2), blocks, ports, marks, 4);
	}
}
