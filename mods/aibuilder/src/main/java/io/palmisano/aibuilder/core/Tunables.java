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
	/** Refinement (spec §5.4): how near (sideways) to the last build a prompt refines it, and how far its box grows. */
	public static final int REFINE_REACH = 48;
	public static final int REFINE_GROW = 16;
	/** Builds per player that can be undone. */
	public static final int UNDO_HISTORY = 5;
	/** The most builds a player's undo history may keep, whatever the config says (its undo_history_size goes to 50). */
	public static final int UNDO_HISTORY_CEILING = 50;
	/** How far the ground search climbs out of solid ground. */
	public static final int GROUND_SEARCH_UP = 16;
	/** How far the ground search falls through air. */
	public static final int GROUND_SEARCH_DOWN = 64;
	/** Ticks between action-bar status updates. */
	public static final int STATUS_INTERVAL_TICKS = 20;
	/** How long a checkpoint waits for its blocks to be placed: well inside Claude Code's 120 s MCP timeout. */
	public static final int CHECKPOINT_TIMEOUT_SECONDS = 90;
	/** terraform's wait for what's queued before it reads the ground again; with that read, inside the MCP timeout. */
	public static final int TERRAFORM_PLACE_WAIT_SECONDS = 40;
	/** Output tokens one build may use (spec §12.2's max_tokens_per_build). */
	public static final long MAX_OUTPUT_TOKENS = 500_000;
	/** Minutes one build may take (spec §12.2's max_build_minutes). */
	public static final int MAX_BUILD_MINUTES = 45;
	/** Past a cap, the tool calls that still get through (finish, say) before the process is ended (spec §12.2). */
	public static final int CAP_GRACE_CALLS = 3;
	/** Past a cap, how long Claude may go on before the process is ended (spec §12.2). */
	public static final int CAP_GRACE_SECONDS = 60;
	/** After SIGINT, how long Claude Code has to end its turn before it's destroyed (spec §12.2). */
	public static final int CANCEL_GRACE_SECONDS = 5;
	/** How long a prompt waits for the last build's Claude Code process to end; it's destroyed after the cancel grace. */
	public static final int PREVIOUS_PROCESS_WAIT_SECONDS = 2 * CANCEL_GRACE_SECONDS;
	/** A build folder this old was left by a game that crashed (a build's lasts as long as its build). */
	public static final int STALE_BUILD_FOLDER_HOURS = 24;
	/** How long Claude Code may take to start (its system/init line) before the build fails. */
	public static final int STARTUP_TIMEOUT_SECONDS = 60;
	/**
	 * With paid extra usage allowed on the account, the fullest usage window this full stops a build before the plan
	 * runs out (Claude Code reports extra usage only after the turn that used it).
	 */
	public static final double USAGE_STOP_UTILIZATION = 0.95;
	/** How long a tool call waits for the build's start check before it's refused (spec §12.2). */
	public static final int READY_WAIT_SECONDS = 10;
	/** Placements queued and not yet placed, at most: beyond it a write call is refused until placement catches up. */
	public static final long PENDING_MAX = 2_000_000;
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
	/** check_build: problems listed by position at most (spec §8); the rest are counted. */
	public static final int CHECK_BUILD_LISTED = 60;
	/** check_build's look over the build (read over ticks), after terraform's wait for placement: inside the MCP timeout. */
	public static final int CHECK_BUILD_TIMEOUT_SECONDS = 60;
	/** How long check_build waits for the light engine to catch up with what was placed before it reads light. */
	public static final int CHECK_BUILD_LIGHT_WAIT_SECONDS = 5;
	/** check_build's "indoors": sky light at most this (open sky is 15, under an overhang in the open 14). */
	public static final int CHECK_BUILD_INDOOR_SKY_LIGHT = 13;
	/** How long undo waits for a far build's item frames to load (entities load after their chunk) before going on. */
	public static final int UNDO_ENTITY_WAIT_SECONDS = 10;
	/** Placement feel (spec §9): blocks a tick that get a sound and a puff of particles; how loud next to a player's; how many particles. */
	public static final int FEEL_PER_TICK = 2;
	public static final float FEEL_VOLUME = 0.5f;
	public static final int FEEL_PARTICLES = 4;

	private Tunables() {
	}
}
