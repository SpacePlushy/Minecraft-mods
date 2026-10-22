package io.palmisano.aibuilder.core.place;

import java.util.List;
import java.util.Set;

/** Blocks that change into each other by themselves count as the same for undo: grass spreads, water freezes. */
public final class BlockEquivalence {
	private static final List<Set<String>> GROUPS = List.of(
			Set.of("minecraft:dirt", "minecraft:grass_block", "minecraft:podzol", "minecraft:mycelium", "minecraft:dirt_path",
					"minecraft:coarse_dirt", "minecraft:rooted_dirt"),
			Set.of("minecraft:water", "minecraft:ice", "minecraft:frosted_ice"));

	private BlockEquivalence() {
	}

	public static boolean same(String blockIdA, String blockIdB) {
		return blockIdA.equals(blockIdB) || GROUPS.stream().anyMatch(group -> group.contains(blockIdA) && group.contains(blockIdB));
	}
}
