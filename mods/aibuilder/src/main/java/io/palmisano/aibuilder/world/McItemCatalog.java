package io.palmisano.aibuilder.world;

import java.util.Optional;
import java.util.Set;

import io.palmisano.aibuilder.core.blocks.ItemCatalog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BannerPattern;

/**
 * The game's items and the world's banner patterns (a data-driven registry: data packs add to it). Registries don't
 * change once the server runs, so any thread may read them.
 */
public final class McItemCatalog implements ItemCatalog {
	/** Items only creative mode has, besides spawn eggs and unbreakable blocks. */
	private static final Set<String> CREATIVE_ONLY = Set.of("minecraft:debug_stick", "minecraft:knowledge_book", "minecraft:command_block_minecart",
			"minecraft:structure_void");

	private final Registry<BannerPattern> patterns;
	private final Set<String> bannedBlocks;

	public McItemCatalog(RegistryAccess registries, Set<String> bannedBlocks) {
		this.patterns = registries.lookupOrThrow(Registries.BANNER_PATTERN);
		this.bannedBlocks = Set.copyOf(bannedBlocks);
	}

	@Override
	public int maxStack(String id) {
		return item(id).map(Item::getDefaultMaxStackSize).orElse(0);
	}

	@Override
	public boolean holdsItems(String id) {
		return item(id).map(item -> item instanceof BundleItem || item instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock)
				.orElse(false);
	}

	@Override
	public boolean banned(String id) {
		Optional<Item> found = item(id);
		if (found.isEmpty()) {
			return false;
		}
		Item item = found.get();
		if (CREATIVE_ONLY.contains(id) || item instanceof SpawnEggItem) {
			return true;
		}
		return item instanceof BlockItem block && (bannedBlocks.contains(BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString())
				|| block.getBlock().defaultBlockState().getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0);
	}

	@Override
	public boolean isBannerPattern(String id) {
		Identifier key = Identifier.tryParse(id);
		return key != null && patterns.containsKey(key);
	}

	private static Optional<Item> item(String id) {
		Identifier key = Identifier.tryParse(id);
		return key == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(key).filter(item -> item != Items.AIR);
	}
}
