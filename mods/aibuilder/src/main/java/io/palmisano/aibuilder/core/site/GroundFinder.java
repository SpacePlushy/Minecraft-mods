package io.palmisano.aibuilder.core.site;

import io.palmisano.aibuilder.core.Tunables;

/**
 * Where a build's y=0 goes (spec §5.4): the floor nearest the player's feet height, so the Nether, caves and indoor
 * builds work. From the feet, up out of solid ground or down through open air to the first floor; over water, the
 * water's surface; with no floor in range, the block under the feet.
 */
public final class GroundFinder {
	private GroundFinder() {
	}

	public static int find(ColumnProbe column, int feetY) {
		Cell feet = column.at(feetY);
		if (feet == Cell.GROUND) {
			for (int y = feetY; y <= feetY + Tunables.GROUND_SEARCH_UP; y++) {
				if (column.at(y + 1) != Cell.GROUND) {
					return y;
				}
			}
		} else if (feet == Cell.OPEN) {
			for (int y = feetY - 1; y >= feetY - Tunables.GROUND_SEARCH_DOWN; y--) {
				if (column.at(y) != Cell.OPEN) {
					return y;
				}
			}
		}
		return feetY - 1;
	}
}
