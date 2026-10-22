package io.palmisano.aibuilder.core.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.Box;
import org.junit.jupiter.api.Test;

class ProtectionMaskTest {
	@Test
	void onlyMarkedPositionsInsideTheBoxAreProtected() {
		ProtectionMask mask = new ProtectionMask(Box.of(-2, 0, 0, 2, 3, 4));
		mask.protect(new Pos(1, 2, 3));
		assertTrue(mask.isProtected(new Pos(1, 2, 3)));
		assertFalse(mask.isProtected(new Pos(1, 2, 2)));
		assertFalse(mask.isProtected(new Pos(9, 9, 9)), "outside the box nothing is protected");
		assertEquals(1, mask.count());
		mask.release(new Pos(1, 2, 3));
		assertFalse(mask.isProtected(new Pos(1, 2, 3)));
	}

	/** Bedrock, barriers…: protected, and no release (a clear that includes protected blocks) frees them. */
	@Test
	void unbreakableStaysProtected() {
		ProtectionMask mask = new ProtectionMask(Box.of(0, 0, 0, 1, 1, 1));
		mask.unbreakable(new Pos(0, 0, 0));
		mask.release(new Pos(0, 0, 0));
		assertTrue(mask.isProtected(new Pos(0, 0, 0)) && mask.isUnbreakable(new Pos(0, 0, 0)));
	}
}
