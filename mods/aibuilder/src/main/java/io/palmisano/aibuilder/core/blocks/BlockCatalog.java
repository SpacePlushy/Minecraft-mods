package io.palmisano.aibuilder.core.blocks;

import java.util.Collection;
import java.util.Optional;

/** Every block the game knows, other mods' included. */
public interface BlockCatalog {
	/** The block with this normalized id ({@code namespace:path}). */
	Optional<BlockInfo> find(String id);

	Collection<String> ids();
}
