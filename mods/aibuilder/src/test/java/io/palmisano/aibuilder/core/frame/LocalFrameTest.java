package io.palmisano.aibuilder.core.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class LocalFrameTest {
	/** Expectations derived by hand from Minecraft's axes: +X is east, +Z is south. */
	@Test
	void rightAndForwardFollowTheFacing() {
		Pos o = new Pos(10, 64, 20);
		assertEquals(new Pos(9, 64, 20), new LocalFrame(o, Facing.SOUTH).toWorld(new Pos(1, 0, 0)));
		assertEquals(new Pos(10, 64, 21), new LocalFrame(o, Facing.SOUTH).toWorld(new Pos(0, 0, 1)));
		assertEquals(new Pos(10, 64, 19), new LocalFrame(o, Facing.WEST).toWorld(new Pos(1, 0, 0)));
		assertEquals(new Pos(9, 64, 20), new LocalFrame(o, Facing.WEST).toWorld(new Pos(0, 0, 1)));
		assertEquals(new Pos(11, 64, 20), new LocalFrame(o, Facing.NORTH).toWorld(new Pos(1, 0, 0)));
		assertEquals(new Pos(10, 64, 19), new LocalFrame(o, Facing.NORTH).toWorld(new Pos(0, 0, 1)));
		assertEquals(new Pos(10, 64, 21), new LocalFrame(o, Facing.EAST).toWorld(new Pos(1, 0, 0)));
		assertEquals(new Pos(11, 64, 20), new LocalFrame(o, Facing.EAST).toWorld(new Pos(0, 0, 1)));
		assertEquals(new Pos(10, 67, 20), new LocalFrame(o, Facing.EAST).toWorld(new Pos(0, 3, 0)));
	}

	@Test
	void toLocalUndoesToWorld() {
		Pos o = new Pos(-5, 70, 12);
		for (Facing facing : Facing.values()) {
			LocalFrame frame = new LocalFrame(o, facing);
			for (Pos p : List.of(new Pos(0, 0, 0), new Pos(3, -2, 5), new Pos(-4, 7, -1))) {
				assertEquals(p, frame.toLocal(frame.toWorld(p)), facing + " " + p);
			}
		}
	}
}
