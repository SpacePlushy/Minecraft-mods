package io.palmisano.aibuilder.core.blocks;

import java.util.List;
import java.util.Map;

/**
 * What the catalog knows about a block, in vanilla property names and values; {@code flammable}: lava can set it
 * alight (vanilla's {@code ignitedByLava}).
 */
public record BlockInfo(String id, Map<String, List<String>> properties, boolean unbreakable, PlacementPass pass, boolean flammable) {
	public static final String LAVA = "minecraft:lava";
}
