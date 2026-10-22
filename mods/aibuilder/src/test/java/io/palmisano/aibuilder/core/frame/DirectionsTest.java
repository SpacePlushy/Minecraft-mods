package io.palmisano.aibuilder.core.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DirectionsTest {
	@Test
	void tokensMapAsIfThePlayerFacedSouth() {
		assertEquals("south", Directions.toTemplateToken("forward"));
		assertEquals("north", Directions.toTemplateToken("back"));
		assertEquals("west", Directions.toTemplateToken("right"));
		assertEquals("east", Directions.toTemplateToken("left"));
		assertEquals("up", Directions.toTemplateToken("up"));
		assertEquals("bottom", Directions.toTemplateToken("bottom"));
	}

	@Test
	void joinedValuesTranslateTokenByToken() {
		assertEquals("north_south", Directions.toTemplateValue("back_forward"));
		assertEquals("ascending_west", Directions.toTemplateValue("ascending_right"));
		assertEquals("back_forward", Directions.toLocalValue("north_south"));
	}

	@Test
	void compassWordsAreSpotted() {
		assertTrue(Directions.mentionsCompass("east"));
		assertTrue(Directions.mentionsCompass("ascending_north"));
		assertFalse(Directions.mentionsCompass("forward"));
		assertFalse(Directions.mentionsCompass("inner_left"));
	}
}
