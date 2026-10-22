package io.palmisano.aibuilder.core.site;

import java.util.Optional;

import io.palmisano.aibuilder.core.Tunables;
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

	/**
	 * A refinement's box (spec §5.4): everything the build has changed so far ({@code changed}, local), grown by 16 on
	 * every side, down by at most a quarter of the tier's size, clipped to the world's heights as above.
	 */
	public static Box refined(Box changed, SizeTier tier, int originY, int minY, int topY) {
		int grow = Tunables.REFINE_GROW;
		int bottom = Math.max(changed.min().y() - Math.min(grow, tier.size() / 4), minY - originY);
		int top = Math.min(changed.max().y() + grow, topY - originY);
		return Box.of(changed.min().x() - grow, Math.min(bottom, top), changed.min().z() - grow, changed.max().x() + grow, top,
				changed.max().z() + grow);
	}
}
