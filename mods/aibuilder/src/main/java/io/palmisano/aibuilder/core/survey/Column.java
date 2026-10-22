package io.palmisano.aibuilder.core.survey;

/**
 * One column of the surveyed area, in the local frame's y: the floor (spec §5.4's ground rule, searched from the
 * origin's height), the highest block that isn't air, a ceiling above the floor inside the allowed box, the floor's
 * block, how deep water or lava is at the floor, whether a tree stands on it, and the protected (probably
 * player-made) blocks inside the allowed box with the most common one.
 */
public record Column(int floor, int top, int ceiling, String floorBlock, int water, int lava, boolean tree, int protectedCount,
		String protectedBlock) {
	/** No ceiling. */
	public static final int NONE = Integer.MAX_VALUE;
}
