package io.palmisano.aibuilder.core.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FacingTest {
	@Test
	void yawSnapsToTheNearestQuarter() {
		assertEquals(Facing.SOUTH, Facing.fromYaw(0f));
		assertEquals(Facing.SOUTH, Facing.fromYaw(44.9f));
		assertEquals(Facing.WEST, Facing.fromYaw(45.1f));
		assertEquals(Facing.NORTH, Facing.fromYaw(180f));
		assertEquals(Facing.NORTH, Facing.fromYaw(-180f));
		assertEquals(Facing.EAST, Facing.fromYaw(-90f));
		assertEquals(Facing.EAST, Facing.fromYaw(270f));
		assertEquals(Facing.WEST, Facing.fromYaw(810f));
	}
}
