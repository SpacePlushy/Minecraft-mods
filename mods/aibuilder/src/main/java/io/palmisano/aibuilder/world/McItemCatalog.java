package io.palmisano.aibuilder.world;

import java.util.Optional;

import io.palmisano.aibuilder.core.blocks.ItemCatalog;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BannerPattern;

/**
 * The game's items and the world's banner patterns (a data-driven registry: data packs add to it). Registries don't
 * change once the server runs, so any thread may read them.
 */
public final class McItemCatalog implements ItemCatalog {
	private final Registry<BannerPattern> patterns;

	public McItemCatalog(RegistryAccess registries) {
		this.patterns = registries.lookupOrThrow(Registries.BANNER_PATTERN);
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
	public boolean isBannerPattern(String id) {
		Identifier key = Identifier.tryParse(id);
		return key != null && patterns.containsKey(key);
	}

	private static Optional<Item> item(String id) {
		Identifier key = Identifier.tryParse(id);
		return key == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(key).filter(item -> item != Items.AIR);
	}
}
