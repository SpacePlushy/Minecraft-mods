package io.palmisano.aibuilder.core.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import org.junit.jupiter.api.Test;

class BuildBoxTest {
	@Test
	void tierSizesTheAllowedBox() {
		assertEquals(Box.of(-24, -12, 0, 23, 47, 47), BuildBox.allowed(SizeTier.MEDIUM, 64, -64, 319).orElseThrow());
	}

	@Test
	void worldLimitsClipTheBox() {
		assertEquals(19, BuildBox.allowed(SizeTier.MEDIUM, 300, -64, 319).orElseThrow().max().y());
		assertEquals(-4, BuildBox.allowed(SizeTier.MEDIUM, -60, -64, 319).orElseThrow().min().y());
	}

	/**
	 * A refinement's box (spec §5.4): what the build changed, grown by 16 on every side but down by at most a quarter of
	 * the tier's size, inside the world's heights.
	 */
	@Test
	void refinedBoxGrowsSixteenDownAtMostAQuarter() {
		Box hut = Box.of(-1, 0, 1, 1, 2, 3);
		assertEquals(Box.of(-17, -6, -15, 17, 18, 19), BuildBox.refined(hut, SizeTier.SMALL, 64, -64, 319), "Small: down 6 (24 / 4)");
		assertEquals(Box.of(-17, -12, -15, 17, 18, 19), BuildBox.refined(hut, SizeTier.MEDIUM, 64, -64, 319), "Medium: down 12");
		assertEquals(Box.of(-17, -16, -15, 17, 18, 19), BuildBox.refined(hut, SizeTier.LARGE, 64, -64, 319), "Large: down 16 (not 20)");
		assertEquals(Box.of(-17, -3, -15, 17, 18, 19), BuildBox.refined(hut, SizeTier.LARGE, -61, -64, 319), "the world's floor");
		assertEquals(Box.of(-17, -16, -15, 17, 4, 19), BuildBox.refined(hut, SizeTier.LARGE, 315, -64, 319), "the build limit");
	}

	/** Flying above the build limit or below the world: no room at all, rather than a box turned upside down. */
	@Test
	void outsideTheWorldsHeightThereIsNoBox() {
		assertTrue(BuildBox.allowed(SizeTier.AUTO, 399, -64, 319).isEmpty());
		assertTrue(BuildBox.allowed(SizeTier.AUTO, -150, -64, 319).isEmpty());
	}
}
