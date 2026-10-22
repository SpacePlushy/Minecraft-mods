package io.palmisano.aibuilder.core.site;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

/**
 * The floor of one survey column (M3 review): searched from the origin's height like the origin's own, but a
 * mountain climbs to the box's top, water on raised ground is read to its surface, and a drop with nothing below is
 * no floor at all.
 */
class SurveyFloorTest {
	/** Bottom up from y 0: '#' ground, '~' water or lava, '.' open; above the picture open, below it {@code below}. */
	private static ColumnProbe column(String cells, Cell below) {
		return y -> y < 0 ? below : y >= cells.length() ? Cell.OPEN : switch (cells.charAt(y)) {
			case '#' -> Cell.GROUND;
			case '~' -> Cell.FLUID;
			default -> Cell.OPEN;
		};
	}

	private static ColumnProbe column(String cells) {
		return column(cells, Cell.GROUND);
	}

	@Test
	void flatGroundIsTheFloor() {
		assertEquals(OptionalInt.of(0), SurveyFloor.find(column("#...."), 1, 40));
	}

	/** The origin's rule gives up 16 blocks up and falls back to the feet: a mountain read as a plateau at 0. */
	@Test
	void aMountainIsClimbedToItsTop() {
		assertEquals(OptionalInt.of(20), SurveyFloor.find(column("#".repeat(21) + "...."), 1, 40));
	}

	@Test
	void aMountainTallerThanTheBoxStopsAtTheBoxTop() {
		assertEquals(OptionalInt.of(40), SurveyFloor.find(column("#".repeat(61)), 1, 40));
	}

	@Test
	void waterOnRaisedGroundIsItsSurface() {
		assertEquals(OptionalInt.of(3), SurveyFloor.find(column("##~~..."), 1, 40));
	}

	@Test
	void fromAboveTheFirstThingBelowIsTheFloor() {
		assertEquals(OptionalInt.of(2), SurveyFloor.find(column("#~~......"), 6, 40));
		assertEquals(OptionalInt.of(0), SurveyFloor.find(column("#........"), 6, 40));
	}

	@Test
	void inFluidTheSurfaceIsTheFloor() {
		assertEquals(OptionalInt.of(3), SurveyFloor.find(column("#~~~.."), 1, 40));
	}

	@Test
	void underARoofTheFloorWins() {
		// A cave: floor at 0, a roof from 5 up; searching from 1 finds the floor, not the roof.
		assertEquals(OptionalInt.of(0), SurveyFloor.find(column("#....######"), 1, 40));
	}

	@Test
	void nothingWithin64BelowIsNoFloor() {
		assertEquals(OptionalInt.empty(), SurveyFloor.find(column("", Cell.OPEN), 1, 40));
		// Ground 65 below the start is out of reach; 64 below is in reach.
		assertEquals(OptionalInt.empty(), SurveyFloor.find(column("#" + ".".repeat(70)), 66, 80));
		assertEquals(OptionalInt.of(0), SurveyFloor.find(column("#" + ".".repeat(70)), 64, 80));
	}
}
