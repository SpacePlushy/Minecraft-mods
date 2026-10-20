package io.palmisano.rocket.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RocketInputTest {
	@Test
	void fromKeysMapsEachKey() {
		assertEquals(1, RocketInput.fromKeys(true, false, false, false, false, 0).forward());
		assertEquals(-1, RocketInput.fromKeys(false, true, false, false, false, 0).forward());
		assertEquals(1, RocketInput.fromKeys(false, false, false, true, false, 0).strafe());
		assertEquals(-1, RocketInput.fromKeys(false, false, true, false, false, 0).strafe());
		assertTrue(RocketInput.fromKeys(false, false, false, false, true, 0).throttleUp());
		assertFalse(RocketInput.fromKeys(true, true, true, true, false, 0).throttleUp());
		assertEquals(123, RocketInput.fromKeys(false, false, false, false, false, 123).targetYaw());
	}

	@Test
	void opposingKeysCancel() {
		RocketInput in = RocketInput.fromKeys(true, true, true, true, false, 0);
		assertEquals(0, in.forward());
		assertEquals(0, in.strafe());
	}

	@Test
	void constructorClamps() {
		RocketInput in = new RocketInput(2, -3, false, 0);
		assertEquals(1, in.forward());
		assertEquals(-1, in.strafe());
	}

	@Test
	void idleHasNoInput() {
		assertEquals(new RocketInput(0, 0, false, 45), RocketInput.idle(45));
	}
}
