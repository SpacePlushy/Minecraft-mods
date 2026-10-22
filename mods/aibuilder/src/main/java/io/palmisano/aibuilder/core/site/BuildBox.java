package io.palmisano.aibuilder.core.site;

import java.util.Optional;

import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;

/** The allowed box of a new build (spec §5.4), in the local frame. */
public final class BuildBox {
	private BuildBox() {
	}

	/**
	 * x from −W/2 to W/2−1, z from 0 to W−1, y from −W/4 to W−1, where W is the tier's size, clipped to the world's
	 * buildable heights {@code minY}..{@code topY} (both inclusive, in world y). Empty when the clipping leaves no
	 * height at all: the origin is far above the build limit or below the world.
	 */
	public static Optional<Box> allowed(SizeTier tier, int originY, int minY, int topY) {
		int w = tier.size();
		int bottom = Math.max(-w / 4, minY - originY);
		int top = Math.min(w - 1, topY - originY);
		if (top < bottom) {
			return Optional.empty();
		}
		return Optional.of(Box.of(-w / 2, bottom, 0, w / 2 - 1, top, w - 1));
	}
}
