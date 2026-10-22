package io.palmisano.aibuilder.core.site;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Where a build's y=0 goes: the floor nearest the player's feet (spec §5.4). */
class GroundFinderTest {
	private static ColumnProbe groundUpTo(int top) {
		return y -> y <= top ? Cell.GROUND : Cell.OPEN;
	}

	@Test
	void onGrassTheGroundIsUnderTheFeet() {
		assertEquals(63, GroundFinder.find(groundUpTo(63), 64));
	}

	@Test
	void aHillAheadClimbsOut() {
		assertEquals(70, GroundFinder.find(groundUpTo(70), 64));
	}

	@Test
	void aDropAheadFindsTheFloorBelow() {
		assertEquals(50, GroundFinder.find(y -> y == 50 ? Cell.GROUND : Cell.OPEN, 64));
	}

	@Test
	void waterIsTheSurface() {
		assertEquals(62, GroundFinder.find(y -> y <= 59 ? Cell.GROUND : y <= 62 ? Cell.FLUID : Cell.OPEN, 64));
	}

	@Test
	void underACavernRoofTheFloorWins() {
		assertEquals(60, GroundFinder.find(y -> y <= 60 || (y >= 71 && y <= 80) ? Cell.GROUND : Cell.OPEN, 61));
	}

	@Test
	void nothingInRangeFallsBack() {
		assertEquals(63, GroundFinder.find(y -> Cell.OPEN, 64));
	}

	@Test
	void deepInRockFallsBack() {
		assertEquals(63, GroundFinder.find(y -> Cell.GROUND, 64));
	}
}
