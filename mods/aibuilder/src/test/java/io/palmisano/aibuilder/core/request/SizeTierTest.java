package io.palmisano.aibuilder.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SizeTierTest {
	@Test
	void parseIsLenientAndDefaultsToAuto() {
		assertEquals(SizeTier.MEDIUM, SizeTier.parse("medium"));
		assertEquals(SizeTier.EPIC, SizeTier.parse("EPIC"));
		assertEquals(SizeTier.AUTO, SizeTier.parse(null));
		assertEquals(SizeTier.AUTO, SizeTier.parse("huge"));
	}
}
