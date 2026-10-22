package io.palmisano.aibuilder.core;

/** Every number the build engine is tuned with (M6 moves them into config/aibuilder.json). */
public final class Tunables {
	/** Blocks a build places per server tick, at most. */
	public static final int BLOCKS_PER_TICK = 250;
	/** Time a build may spend placing per server tick, at most. */
	public static final long TICK_BUDGET_NANOS = 3_000_000;
	/** Blocks between the player's feet and the site's origin. */
	public static final int SITE_GAP = 3;
	/** Tool calls one build may make. */
	public static final int MAX_TOOL_CALLS = 150;
	/** Entries one set_blocks call may hold. */
	public static final int SET_BLOCKS_MAX = 2000;
	/** Builds per player that can be undone. */
	public static final int UNDO_HISTORY = 5;
	/** How far the ground search climbs out of solid ground. */
	public static final int GROUND_SEARCH_UP = 16;
	/** How far the ground search falls through air. */
	public static final int GROUND_SEARCH_DOWN = 64;
	/** Ticks between action-bar status updates. */
	public static final int STATUS_INTERVAL_TICKS = 20;
	/** How long a checkpoint waits for its blocks to be placed. */
	public static final int CHECKPOINT_TIMEOUT_SECONDS = 120;
	/**
	 * The largest number a tool argument may hold, either sign. Coordinates and sizes beyond it are mistakes, and the
	 * limit keeps every shape's loops far from int overflow.
	 */
	public static final int ARGUMENT_LIMIT = 1_000_000;
	/** {@code /aibuild test}'s rate: slow enough to watch the house go up, check on it and cancel it (about 10 s). */
	public static final int DEMO_BLOCKS_PER_TICK = 2;
	/**
	 * Ticks after a block entity is placed before its data is taken as what the build left there: some change on their
	 * first tick (a hopper's transfer cooldown), and undo must still recognise them as the build's own.
	 */
	public static final int BLOCK_ENTITY_SETTLE_TICKS = 2;

	private Tunables() {
	}
}
