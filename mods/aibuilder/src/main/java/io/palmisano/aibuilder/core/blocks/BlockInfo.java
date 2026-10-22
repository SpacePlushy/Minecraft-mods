package io.palmisano.aibuilder.core.blocks;

import java.util.List;
import java.util.Map;

/** What the catalog knows about a block, in vanilla property names and values. */
public record BlockInfo(String id, Map<String, List<String>> properties, boolean unbreakable, PlacementPass pass) {
}
