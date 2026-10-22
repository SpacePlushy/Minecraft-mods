package io.palmisano.aibuilder.core.blocks;

import java.util.List;
import java.util.Map;

/**
 * What the catalog knows about a block, in vanilla property names and values; {@code flammable}: lava can set it
 * alight (vanilla's {@code ignitedByLava}); {@code entity}: the block entity it has, for place_block_entity.
 */
public record BlockInfo(String id, Map<String, List<String>> properties, boolean unbreakable, PlacementPass pass, boolean flammable,
		BlockEntityKind entity) {
	public static final String LAVA = "minecraft:lava";

	/** A block without a block entity. */
	public BlockInfo(String id, Map<String, List<String>> properties, boolean unbreakable, PlacementPass pass, boolean flammable) {
		this(id, properties, unbreakable, pass, flammable, BlockEntityKind.NONE);
	}
}
