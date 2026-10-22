package io.palmisano.aibuilder.core.site;

import java.util.OptionalInt;

import io.palmisano.aibuilder.core.Tunables;

/**
 * The floor of one survey column (spec §11, as the M3 review corrected it). Searched from just above the origin's
 * ground the way the origin's own is (§5.4), with three differences that matter across a whole survey: solid ground
 * is climbed as far as the box's top, so a mountain reads as a mountain and not as a plateau at the feet; water or lava
 * lying on that ground counts up to its surface; and with nothing within {@link Tunables#GROUND_SEARCH_DOWN} below,
 * there is no floor at all rather than a made-up one.
 */
public final class SurveyFloor {
	private SurveyFloor() {
	}

	/** The floor's y, or empty for a drop with nothing in reach below; never above {@code topY} when climbing. */
	public static OptionalInt find(ColumnProbe column, int startY, int topY) {
		if (column.at(startY) == Cell.OPEN) {
			for (int y = startY - 1; y >= startY - Tunables.GROUND_SEARCH_DOWN; y--) {
				if (column.at(y) != Cell.OPEN) {
					return OptionalInt.of(y);
				}
			}
			return OptionalInt.empty();
		}
		// In solid ground or a fluid: up through both to the first open cell.
		int y = startY;
		while (y < topY && column.at(y + 1) != Cell.OPEN) {
			y++;
		}
		return OptionalInt.of(y);
	}
}
