package io.palmisano.aibuilder.core.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import org.junit.jupiter.api.Test;

/** The columns a build's site survey reads, so protection and the lava rule cover the whole allowed box (spec §9, §11). */
class SiteSquareTest {
	@Test
	void aNewBuildsSquareIsTheTiersSiteWithItsMargin() {
		Box allowed = BuildBox.allowed(SizeTier.MEDIUM, 64, -64, 320).orElseThrow();
		assertEquals(new SiteSquare(-32, -8, 65), SiteSquare.around(allowed, SizeTier.MEDIUM, 8));
	}

	/** The M5 live castle's first refinement: its changes grown by 16 are wider than a Medium site (M6 review). */
	@Test
	void aRefinedBoxIsCoveredToItsEdges() {
		Box refined = Box.of(-39, -12, -16, 39, 47, 63);
		SiteSquare square = SiteSquare.around(refined, SizeTier.MEDIUM, 8);
		assertTrue(square.minX() <= -39 - 8 && square.minX() + square.size() - 1 >= 39 + 8, square.toString());
		assertTrue(square.minZ() <= -16 - 8 && square.minZ() + square.size() - 1 >= 63 + 8, square.toString());
		assertTrue(square.covers(refined, 8));
	}

	@Test
	void coversNeedsTheMarginToo() {
		assertTrue(new SiteSquare(-10, -10, 21).covers(Box.of(-5, 0, -5, 5, 3, 5), 5));
		assertTrue(!new SiteSquare(-10, -10, 21).covers(Box.of(-5, 0, -5, 6, 3, 5), 5));
	}
}
