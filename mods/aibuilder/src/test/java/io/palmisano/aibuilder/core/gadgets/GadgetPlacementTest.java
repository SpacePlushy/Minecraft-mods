package io.palmisano.aibuilder.core.gadgets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.shapes.LocalBlock;
import org.junit.jupiter.api.Test;

/** A gadget turned to each facing: its blocks, their direction words and its ports turn together. */
class GadgetPlacementTest {
	private static final Gadget SMALL = new Gadget("small", new Pos(3, 1, 2),
			List.of(new LocalBlock(new Pos(0, 0, 0), new BlockSpec("repeater", Map.of("facing", "back"))),
					new LocalBlock(new Pos(2, 0, 1), new BlockSpec("oak_log", Map.of("axis", "x"))),
					new LocalBlock(new Pos(1, 0, 1), new BlockSpec("observer", Map.of("facing", "up")))),
			List.of(new Port("in", true, new Pos(0, 0, -1), "a signal")), 2);

	private static GadgetPlacement.Placed at(String facing) {
		return GadgetPlacement.place(SMALL, new Pos(10, 0, 20), facing);
	}

	@Test
	void facingsTurnBlocksAndPorts() {
		GadgetPlacement.Placed forward = at("forward");
		assertEquals(List.of(new LocalBlock(new Pos(10, 0, 20), new BlockSpec("repeater", Map.of("facing", "back"))),
				new LocalBlock(new Pos(12, 0, 21), new BlockSpec("oak_log", Map.of("axis", "x"))),
				new LocalBlock(new Pos(11, 0, 21), new BlockSpec("observer", Map.of("facing", "up")))), forward.blocks());
		assertEquals(new Pos(10, 0, 19), forward.ports().getFirst().at());
		assertEquals(Box.of(10, 0, 20, 12, 0, 21), forward.box());

		// Turned right: its forward is the build's right, its right the build's back.
		GadgetPlacement.Placed right = at("RIGHT");
		assertEquals(List.of(new LocalBlock(new Pos(10, 0, 20), new BlockSpec("repeater", Map.of("facing", "left"))),
				new LocalBlock(new Pos(11, 0, 18), new BlockSpec("oak_log", Map.of("axis", "z"))),
				new LocalBlock(new Pos(11, 0, 19), new BlockSpec("observer", Map.of("facing", "up")))), right.blocks());
		assertEquals(new Pos(9, 0, 20), right.ports().getFirst().at());
		assertEquals(Box.of(10, 0, 18, 11, 0, 20), right.box());

		GadgetPlacement.Placed back = at("back");
		assertEquals(new LocalBlock(new Pos(10, 0, 20), new BlockSpec("repeater", Map.of("facing", "forward"))), back.blocks().getFirst());
		assertEquals(new Pos(8, 0, 19), back.blocks().get(1).pos());
		assertEquals(new Pos(10, 0, 21), back.ports().getFirst().at());
		assertEquals(Box.of(8, 0, 19, 10, 0, 20), back.box());

		GadgetPlacement.Placed left = at("left");
		assertEquals(new LocalBlock(new Pos(10, 0, 20), new BlockSpec("repeater", Map.of("facing", "right"))), left.blocks().getFirst());
		assertEquals(new LocalBlock(new Pos(9, 0, 22), new BlockSpec("oak_log", Map.of("axis", "z"))), left.blocks().get(1));
		assertEquals(new Pos(11, 0, 20), left.ports().getFirst().at());
		assertEquals(Box.of(9, 0, 20, 10, 0, 22), left.box());
	}

	@Test
	void anUnknownFacingIsRefused() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> at("north"));
		assertEquals("facing must be forward, right, back or left, not \"north\"", e.getMessage());
	}
}
