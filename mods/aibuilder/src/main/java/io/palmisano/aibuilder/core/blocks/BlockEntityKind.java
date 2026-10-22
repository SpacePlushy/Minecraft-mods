package io.palmisano.aibuilder.core.blocks;

/** What kind of block entity a block has, for place_block_entity (spec §8): the data it can take. */
public enum BlockEntityKind {
	NONE,
	SIGN,
	CONTAINER,
	LECTERN,
	BANNER,
	/** One place_block_entity has nothing for (a furnace's fuel, a beehive's bees). */
	OTHER
}
