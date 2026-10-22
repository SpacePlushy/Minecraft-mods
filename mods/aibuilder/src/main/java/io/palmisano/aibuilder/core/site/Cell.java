package io.palmisano.aibuilder.core.site;

/** What one block of a column is, for finding the ground. */
public enum Cell {
	/** Air, or something a build may stand in: plants, leaves, logs, snow layers. */
	OPEN,
	GROUND,
	/** Water or lava: its surface is the floor, never what's under it. */
	FLUID
}
