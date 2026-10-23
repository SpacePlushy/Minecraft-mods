package io.palmisano.aibuilder.core.gadgets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;

/**
 * A latch (spec v2 §5.3), one bit every 4 blocks: d's buffer, a data repeater D, dust and q's buffer straight back.
 * {@code hold}, at the front right of the last bit, climbs two steps to a bus 2 above the data lines that runs left over
 * them, a repeater before each bit; at each bit the bus steps down twice to a repeater locking D from its right side.
 * While hold is on, every D keeps what it had. Pure.
 */
final class Latch {
	private Latch() {
	}

	static Gadget make(int bits) {
		List<LocalBlock> blocks = new ArrayList<>();
		List<Port> ports = new ArrayList<>();
		List<Port> outputs = new ArrayList<>();
		for (int i = 0; i < bits; i++) {
			int x = 4 * i;
			Pla.stand(blocks, new Pos(x, 1, 0), Pla.repeater("forward"));
			Pla.stand(blocks, new Pos(x, 1, 1), Pla.repeater("forward"));
			for (int z = 2; z <= 4; z++) {
				Pla.stand(blocks, new Pos(x, 1, z), Pla.DUST);
			}
			Pla.stand(blocks, new Pos(x, 1, 5), Pla.repeater("forward"));
			Pla.stand(blocks, new Pos(x + 1, 1, 1), Pla.repeater("left"));
			Pla.stand(blocks, new Pos(x + 2, 1, 1), Pla.DUST);
			Pla.stand(blocks, new Pos(x + 2, 2, 2), Pla.DUST);
			Pla.stand(blocks, new Pos(x + 2, 3, 3), Pla.DUST);
			Pla.stand(blocks, new Pos(x + 3, 3, 3), Pla.repeater("left"));
			if (i > 0) {
				Pla.stand(blocks, new Pos(x, 3, 3), Pla.DUST);
				Pla.stand(blocks, new Pos(x + 1, 3, 3), Pla.DUST);
			}
			ports.add(new Port("d" + i, true, new Pos(x, 1, -1), "bit " + i + " in"));
			outputs.add(new Port("q" + i, false, new Pos(x, 1, 6), "bit " + i + ": d" + i + ", or what it was as hold turned on"));
		}
		int h = 4 * bits;
		Pla.stand(blocks, new Pos(h, 1, 0), Pla.repeater("forward"));
		Pla.stand(blocks, new Pos(h, 1, 1), Pla.DUST);
		Pla.stand(blocks, new Pos(h, 2, 2), Pla.DUST);
		Pla.stand(blocks, new Pos(h, 3, 3), Pla.DUST);
		ports.add(new Port("hold", true, new Pos(h, 1, -1), "while on, the outputs keep what they had"));
		ports.addAll(outputs);
		return new Gadget("latch", new Pos(h + 1, 4, 6), blocks, ports, Map.of(), 12);
	}
}
