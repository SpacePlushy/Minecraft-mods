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

	private Tunables() {
	}
}
