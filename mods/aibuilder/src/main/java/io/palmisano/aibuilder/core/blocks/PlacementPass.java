package io.palmisano.aibuilder.core.blocks;

/** Placement order inside a batch: supports first, things that hang off them last. */
public enum PlacementPass {
	STRUCTURE,
	PARTIAL,
	FALLING,
	FLUID,
	ATTACHED
}
