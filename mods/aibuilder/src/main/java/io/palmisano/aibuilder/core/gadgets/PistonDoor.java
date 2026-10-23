package io.palmisano.aibuilder.core.gadgets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;

/**
 * A 2 × 2 piston door (spec v2 §5.3) in a 6-wide frame of stone bricks at the gadget's front: two sticky pistons each
 * side, a door block in front of each, placed back so the pistons push them into the doorway as they start. Behind the
 * frame a torch each side keeps its pistons out (the block above it powers the upper one); {@code open}, behind the
 * door, powers the torches' blocks through a line of dust and turns them off, and the pistons pull the door blocks back.
 * A floor fills the gadget's foot, so the way through stays level. Pure.
 */
final class PistonDoor {
	private static final BlockSpec BRICKS = BlockSpec.of("stone_bricks");

	private PistonDoor() {
	}

	static Gadget make() {
		List<LocalBlock> blocks = new ArrayList<>();
		for (int x = 0; x < 6; x++) {
			for (int z = 0; z < 6; z++) {
				blocks.add(new LocalBlock(new Pos(x, 0, z), BRICKS));
			}
			blocks.add(new LocalBlock(new Pos(x, 3, 0), BRICKS));
		}
		for (int y = 1; y <= 2; y++) {
			blocks.add(new LocalBlock(new Pos(0, y, 0), new BlockSpec("sticky_piston", Map.of("facing", "right"))));
			blocks.add(new LocalBlock(new Pos(1, y, 0), BRICKS));
			blocks.add(new LocalBlock(new Pos(4, y, 0), BRICKS));
			blocks.add(new LocalBlock(new Pos(5, y, 0), new BlockSpec("sticky_piston", Map.of("facing", "left"))));
		}
		for (int x : new int[] {0, 5}) {
			blocks.add(new LocalBlock(new Pos(x, 1, 1), Pla.torch("back")));
			blocks.add(new LocalBlock(new Pos(x, 2, 1), Pla.STONE));
			blocks.add(new LocalBlock(new Pos(x, 1, 2), Pla.STONE));
			blocks.add(new LocalBlock(new Pos(x, 1, 3), Pla.repeater("back")));
		}
		for (int x = 0; x < 6; x++) {
			blocks.add(new LocalBlock(new Pos(x, 1, 4), Pla.DUST));
		}
		blocks.add(new LocalBlock(new Pos(2, 1, 5), Pla.repeater("back")));
		Map<String, Pos> marks = new LinkedHashMap<>();
		marks.put("doorway 0", new Pos(2, 1, 0));
		marks.put("doorway 1", new Pos(3, 1, 0));
		marks.put("doorway 2", new Pos(2, 2, 0));
		marks.put("doorway 3", new Pos(3, 2, 0));
		List<Port> ports = List.of(new Port("open", true, new Pos(2, 1, 6), "opens the door while on"));
		return new Gadget("piston_door", new Pos(6, 4, 6), blocks, ports, marks, 16);
	}
}
