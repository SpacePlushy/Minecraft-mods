package io.palmisano.aibuilder.core.blocks;

/** The game's items and banner patterns, for place_block_entity's checks. Ids are full ({@code minecraft:diamond}). */
public interface ItemCatalog {
	/** The item's largest stack, or 0 when there's no such item. */
	int maxStack(String id);

	/** It holds items of its own (a shulker box, a bundle), so it never goes inside a container. */
	boolean holdsItems(String id);

	boolean isBannerPattern(String id);
}
