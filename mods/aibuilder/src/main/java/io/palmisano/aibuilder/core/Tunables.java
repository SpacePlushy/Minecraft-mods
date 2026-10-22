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
	/** Claude Code's own turn limit: only a backstop above the tool-call cap (spec §12.2). */
	public static final int MAX_TURNS = 200;
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
	/** survey_area's largest radius (spec §8); the default is half the tier's size plus 8. */
	public static final int SURVEY_RADIUS_MAX = 96;
	/** The longest survey text: Claude Code swaps a tool result over 50,000 characters for a file path (spec §8, §11). */
	public static final int SURVEY_MAX_CHARS = 30_000;
	/** Blocks one inspect_region call may read (spec §8). */
	public static final int INSPECT_MAX_BLOCKS = 4096;
	/** How long a read tool waits for the world: a survey (read over ticks), and a region or the facts. */
	public static final int SURVEY_TIMEOUT_SECONDS = 60;
	public static final int READ_TIMEOUT_SECONDS = 10;
	/** search_blocks: results by default, and at most. */
	public static final int SEARCH_LIMIT_DEFAULT = 20;
	public static final int SEARCH_LIMIT_MAX = 50;

	private Tunables() {
	}
}
