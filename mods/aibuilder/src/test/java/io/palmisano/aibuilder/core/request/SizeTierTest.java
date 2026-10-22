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

	@Test
	void autoBuildsLikeLarge() {
		assertEquals(80, SizeTier.AUTO.size());
		assertEquals(SizeTier.LARGE.size(), SizeTier.AUTO.size());
		assertEquals(350_000, SizeTier.AUTO.maxChangedBlocks());
		assertEquals(24, SizeTier.SMALL.size());
		assertEquals(15_000, SizeTier.SMALL.maxChangedBlocks());
	}
}
