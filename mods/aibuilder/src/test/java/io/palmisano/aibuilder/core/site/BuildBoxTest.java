package io.palmisano.aibuilder.core.site;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import org.junit.jupiter.api.Test;

class BuildBoxTest {
	@Test
	void tierSizesTheAllowedBox() {
		assertEquals(Box.of(-24, -12, 0, 23, 47, 47), BuildBox.allowed(SizeTier.MEDIUM, 64, -64, 319));
	}

	@Test
	void worldLimitsClipTheBox() {
		assertEquals(19, BuildBox.allowed(SizeTier.MEDIUM, 300, -64, 319).max().y());
		assertEquals(-4, BuildBox.allowed(SizeTier.MEDIUM, -60, -64, 319).min().y());
	}
}
