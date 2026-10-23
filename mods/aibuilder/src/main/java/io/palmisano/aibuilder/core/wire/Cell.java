package io.palmisano.aibuilder.core.wire;

/** What a spot holds, as a wire sees it (spec v2 §5.4); read through {@code WorldReader.cells}. */
public enum Cell {
	/** Air, or what a block simply takes the place of (grass, a layer of snow): a wire's dust or stone can go in. */
	FREE,
	/** A full block power passes through, its top holding dust: ground a wire runs on. */
	SOLID,
	/** Redstone dust. */
	DUST,
	/** Any other redstone: what gives power or reacts to it. */
	COMPONENT,
	/** Anything else: a wire can't go in it or on it. */
	OTHER
}
