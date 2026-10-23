package io.palmisano.aibuilder.core.place;

import java.util.List;
import java.util.Set;

/**
 * Blocks that change into each other by themselves count as the same for undo: grass spreads, water freezes, copper
 * weathers (exposed, weathered, oxidized; a waxed block has been waxed by someone, so it's another block).
 */
public final class BlockEquivalence {
	private static final List<Set<String>> GROUPS = List.of(
			Set.of("minecraft:dirt", "minecraft:grass_block", "minecraft:podzol", "minecraft:mycelium", "minecraft:dirt_path",
					"minecraft:coarse_dirt", "minecraft:rooted_dirt"),
			Set.of("minecraft:water", "minecraft:ice", "minecraft:frosted_ice"));
	private static final List<String> STAGES = List.of("exposed_", "weathered_", "oxidized_");

	private BlockEquivalence() {
	}

	public static boolean same(String blockIdA, String blockIdB) {
		return blockIdA.equals(blockIdB) || GROUPS.stream().anyMatch(group -> group.contains(blockIdA) && group.contains(blockIdB))
				|| unweathered(blockIdA).equals(unweathered(blockIdB));
	}

	/** A copper block's id as it was before it weathered: {@code exposed_cut_copper} is {@code cut_copper}. */
	private static String unweathered(String id) {
		if (!id.startsWith("minecraft:")) {
			return id;
		}
		String name = id.substring("minecraft:".length());
		for (String stage : STAGES) {
			String rest = name.substring(Math.min(stage.length(), name.length()));
			if (name.startsWith(stage) && (rest.contains("copper") || rest.equals("lightning_rod"))) {
				// The first stage of a plain copper block is copper_block; it weathers to exposed_copper.
				return "minecraft:" + (rest.equals("copper") ? "copper_block" : rest);
			}
		}
		return id;
	}
}
