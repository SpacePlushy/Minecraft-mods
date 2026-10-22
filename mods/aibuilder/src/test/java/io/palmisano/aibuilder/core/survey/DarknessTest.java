package io.palmisano.aibuilder.core.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.palmisano.aibuilder.core.Tunables;
import org.junit.jupiter.api.Test;

/** check_build's "under a roof": where a spot counts as indoors, so dark there means monsters (spec §8). */
class DarknessTest {
	/** Under a sky, the sky light says it: out in the open under an overhang it's still 14. */
	@Test
	void underASkyLittleSkyLightIsIndoors() {
		assertTrue(Darkness.indoors(true, Tunables.CHECK_BUILD_INDOOR_SKY_LIGHT, up -> false));
		assertFalse(Darkness.indoors(true, Tunables.CHECK_BUILD_INDOOR_SKY_LIGHT + 1, up -> true));
	}

	/** The Nether's bedrock ceiling is over every spot, so only a block close above makes a roof. */
	@Test
	void ceilingDimensionsLookUpABoundedWay() {
		assertTrue(Darkness.indoors(false, 0, up -> up == 6));
		assertFalse(Darkness.indoors(false, 0, up -> up == Tunables.CHECK_ROOF_SCAN + 1));
		List<Integer> asked = new ArrayList<>();
		assertFalse(Darkness.indoors(false, 0, up -> {
			asked.add(up);
			return false;
		}));
		// The spot and the block over it are the walkable room; the roof starts above them.
		assertEquals(2, Collections.min(asked));
		assertEquals(Tunables.CHECK_ROOF_SCAN, Collections.max(asked));
	}
}
