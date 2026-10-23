package io.palmisano.aibuilder.core.blocks;

import java.util.Map;
import java.util.Set;

/** A few real items and banner patterns, for tests that need them without the game. */
public final class FakeItems implements ItemCatalog {
	private static final Map<String, Integer> STACKS = Map.ofEntries(Map.entry("minecraft:diamond", 64), Map.entry("minecraft:bread", 64),
			Map.entry("minecraft:iron_sword", 1), Map.entry("minecraft:shulker_box", 1), Map.entry("minecraft:bundle", 1),
			Map.entry("minecraft:writable_book", 1), Map.entry("minecraft:clock", 64), Map.entry("minecraft:bedrock", 64),
			Map.entry("minecraft:lava_bucket", 1), Map.entry("minecraft:cobblestone", 64), Map.entry("minecraft:arrow", 64));
	/** What a dispenser does something with of its own: pours, shoots. */
	private static final Set<String> DISPENSING = Set.of("minecraft:lava_bucket", "minecraft:arrow");
	private static final Set<String> HOLDERS = Set.of("minecraft:shulker_box", "minecraft:bundle");
	private static final Set<String> BANNED = Set.of("minecraft:bedrock");
	private static final Set<String> PATTERNS = Set.of("minecraft:stripe_bottom", "minecraft:creeper", "minecraft:border");

	public static FakeItems standard() {
		return new FakeItems();
	}

	@Override
	public int maxStack(String id) {
		return STACKS.getOrDefault(id, 0);
	}

	@Override
	public boolean holdsItems(String id) {
		return HOLDERS.contains(id);
	}

	@Override
	public boolean banned(String id) {
		return BANNED.contains(id);
	}

	@Override
	public boolean dispenses(String id) {
		return DISPENSING.contains(id);
	}

	@Override
	public boolean isBannerPattern(String id) {
		return PATTERNS.contains(id);
	}
}
