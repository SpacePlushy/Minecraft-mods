package io.palmisano.aibuilder.core.survey;

import java.util.function.IntPredicate;

import io.palmisano.aibuilder.core.Tunables;

/** check_build's "under a roof" (spec §8): where a dark walkable spot is indoors, so monsters would spawn in the build. */
public final class Darkness {
	private Darkness() {
	}

	/**
	 * Under a sky, little of it reaches the spot: out in the open under an overhang (a tower's battlements, a tree) sky
	 * light is still 14, inside a room it's lower. Without one (the Nether, whose ceiling is over every spot), a block
	 * close above it: {@code roofAt(up)} says whether the block {@code up} above the spot is one, asked from 2 (over the
	 * spot's two blocks of room) to {@link Tunables#CHECK_ROOF_SCAN}.
	 */
	public static boolean indoors(boolean hasSky, int skyLight, IntPredicate roofAt) {
		if (hasSky) {
			return skyLight <= Tunables.CHECK_BUILD_INDOOR_SKY_LIGHT;
		}
		for (int up = 2; up <= Tunables.CHECK_ROOF_SCAN; up++) {
			if (roofAt.test(up)) {
				return true;
			}
		}
		return false;
	}
}
