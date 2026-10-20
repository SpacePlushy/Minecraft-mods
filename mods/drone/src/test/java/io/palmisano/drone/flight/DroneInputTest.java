package io.palmisano.drone.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DroneInputTest {
	@Test
	void fromKeysMapsEachKey() {
		assertEquals(new DroneInput(1, 0, 0, 30), DroneInput.fromKeys(true, false, false, false, false, false, 30));
		assertEquals(new DroneInput(-1, 0, 0, 30), DroneInput.fromKeys(false, true, false, false, false, false, 30));
		assertEquals(new DroneInput(0, -1, 0, 30), DroneInput.fromKeys(false, false, true, false, false, false, 30));
		assertEquals(new DroneInput(0, 1, 0, 30), DroneInput.fromKeys(false, false, false, true, false, false, 30));
		assertEquals(new DroneInput(0, 0, 1, 30), DroneInput.fromKeys(false, false, false, false, true, false, 30));
		assertEquals(new DroneInput(0, 0, -1, 30), DroneInput.fromKeys(false, false, false, false, false, true, 30));
	}

	@Test
	void opposingKeysCancel() { // Review Focus 5
		assertEquals(DroneInput.hover(30), DroneInput.fromKeys(true, true, true, true, true, true, 30));
	}

	@Test
	void constructorClamps() {
		assertEquals(new DroneInput(1, -1, 1, 0), new DroneInput(2, -3, 5, 0));
	}

	@Test
	void hoverHasNoStickInput() {
		assertEquals(new DroneInput(0, 0, 0, 45), DroneInput.hover(45));
	}
}
