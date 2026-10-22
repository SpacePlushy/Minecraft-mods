package io.palmisano.aibuilder.core.place;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.Box;
import org.junit.jupiter.api.Test;

/** Where lava can set things alight (spec §9): within 3 blocks sideways, from 1 below it to 4 above it. */
class FireMaskTest {
	private static final Pos LAVA = new Pos(0, 0, 0);

	@Test
	void besideAndAboveIsInDanger() {
		assertTrue(FireMask.inDanger(LAVA, new Pos(3, 0, 3)));
		assertTrue(FireMask.inDanger(LAVA, new Pos(-3, 2, 1)));
		assertTrue(FireMask.inDanger(LAVA, new Pos(0, 4, 0)));
		assertTrue(FireMask.inDanger(LAVA, new Pos(1, -1, 0)));
	}

	@Test
	void fartherIsSafe() {
		assertFalse(FireMask.inDanger(LAVA, new Pos(4, 0, 0)));
		assertFalse(FireMask.inDanger(LAVA, new Pos(0, 0, -4)));
		assertFalse(FireMask.inDanger(LAVA, new Pos(0, 5, 0)));
		assertFalse(FireMask.inDanger(LAVA, new Pos(0, -2, 0)));
	}

	@Test
	void theMaskRemembersAndForgets() {
		FireMask mask = new FireMask(Box.of(-5, -5, -5, 5, 5, 5));
		mask.lava(new Pos(1, 1, 1));
		mask.flammable(new Pos(2, 1, 1));
		assertTrue(mask.isLava(new Pos(1, 1, 1)));
		assertTrue(mask.isFlammable(new Pos(2, 1, 1)));
		assertFalse(mask.isFlammable(new Pos(1, 1, 1)));
		mask.clear(new Pos(2, 1, 1));
		assertFalse(mask.isFlammable(new Pos(2, 1, 1)));
		// Outside the box nothing is known: nothing there.
		assertFalse(mask.isLava(new Pos(50, 0, 0)));
		assertTrue(mask.anyLava());
		assertFalse(FireMask.none().anyLava());
	}
}
