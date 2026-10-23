package io.palmisano.aibuilder.core.anim;

import java.util.List;

import io.palmisano.aibuilder.core.Tunables;

/**
 * A named moving part (spec v2 §4.2): the pivot it turns about, in local block-centre units (an integer is a block's
 * centre, {@code .5} the face between two), its motions in the order they apply, and the part it rides with ("" for
 * none).
 */
public record MovingPart(String name, Vec3 pivot, List<Motion> motions, String parent) {
	public MovingPart {
		motions = List.copyOf(motions);
		parent = parent == null ? "" : parent;
		if (motions.size() > Tunables.MOTIONS_PER_PART_MAX) {
			throw new IllegalArgumentException("a part takes at most " + Tunables.MOTIONS_PER_PART_MAX + " motions");
		}
	}
}
