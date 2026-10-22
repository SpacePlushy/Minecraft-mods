package io.palmisano.aibuilder.core.site;

import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;

/**
 * The square of columns (local x and z) a build's site survey reads, for the terrain Claude sees, what's protected and
 * what burns (spec §9, §11). Centred on the allowed box and {@code margin} past its edges, at least the tier's own
 * site: a refinement's box (its changes grown by 16) can be far wider than its tier (M6 review).
 */
public record SiteSquare(int minX, int minZ, int size) {
	public static SiteSquare around(Box allowed, SizeTier tier, int margin) {
		int centreX = Math.floorDiv(allowed.min().x() + allowed.max().x() + 1, 2);
		int centreZ = Math.floorDiv(allowed.min().z() + allowed.max().z() + 1, 2);
		int reach = Math.max(Math.max(centreX - allowed.min().x(), allowed.max().x() - centreX),
				Math.max(centreZ - allowed.min().z(), allowed.max().z() - centreZ));
		int radius = Math.max(tier.size() / 2, reach) + margin;
		return new SiteSquare(centreX - radius, centreZ - radius, 2 * radius + 1);
	}

	/** Half the square's side: survey_area's default radius. */
	public int radius() {
		return size / 2;
	}

	/** Every column of {@code box}, and {@code margin} more on each side, is in the square. */
	public boolean covers(Box box, int margin) {
		return minX <= box.min().x() - margin && minZ <= box.min().z() - margin && minX + size - 1 >= box.max().x() + margin
				&& minZ + size - 1 >= box.max().z() + margin;
	}
}
