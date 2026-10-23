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
	/** The status panel (spec §5.3): a title, phase name or note shows at most this many characters (Claude's text). */
	public static final int HUD_TEXT_MAX = 48;
	/** Phases the panel lists, at most. */
	public static final int HUD_PHASES_MAX = 12;
	/** The panel goes when no status has come for this long (a lost connection, a crash). */
	public static final int HUD_STALE_SECONDS = 5;
	/** How often a running build's session record catches up with what it has built (a crash loses at most this). */
	public static final int SESSION_REFRESH_TICKS = 100;
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
	/** A passed login check (claude auth status) is trusted this long: each build would otherwise wait on a process. */
	public static final int LOGIN_CHECK_REMEMBER_MINUTES = 5;
	/** How long claude auth status may take before it counts as not answering. */
	public static final int LOGIN_CHECK_TIMEOUT_SECONDS = 15;
	/** The build log (spec §16): arrays in a call's arguments past this many are cut to "… N more". */
	public static final int LOG_ARRAY_LIMIT = 20;
	/** The build log: a tool's answer, its first line, cut to this many characters. */
	public static final int LOG_RESULT_LIMIT = 300;
	/** The build log's file name: the prompt's first words, at most this long. */
	public static final int LOG_SLUG_LIMIT = 40;
	/** Claude Code's error line in a "couldn't run" message, at most this long. */
	public static final int CLAUDE_ERROR_MAX = 200;
	/** The scrubber leaves secrets shorter than this alone: they'd take ordinary words with them. */
	public static final int SCRUB_MIN_SECRET = 8;
	/** A brief's title (chat, the panel, the screen's mode line) is cut to this many characters. */
	public static final int TITLE_MAX = 64;
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
	/** The site survey reads this far past the allowed box on every side (the fire mask needs 3, the terrain some). */
	public static final int SITE_MARGIN = 8;
	/** The longest survey text: Claude Code swaps a tool result over 50,000 characters for a file path (spec §8, §11). */
	public static final int SURVEY_MAX_CHARS = 30_000;
	/** Blocks one inspect_region call may read (spec §8). */
	public static final int INSPECT_MAX_BLOCKS = 4096;
	/** The longest inspect_region text, as the survey's: a line a row, so a tall, narrow box of the most blocks runs past it. */
	public static final int INSPECT_MAX_CHARS = 30_000;
	/** How long a read tool waits for the world: a survey (read over ticks), and a region or the facts. */
	public static final int SURVEY_TIMEOUT_SECONDS = 60;
	public static final int READ_TIMEOUT_SECONDS = 10;
	/** search_blocks: results by default, and at most. */
	public static final int SEARCH_LIMIT_DEFAULT = 20;
	public static final int SEARCH_LIMIT_MAX = 50;
	/** check_build: problems listed by position at most (spec §8); the rest are counted. */
	public static final int CHECK_BUILD_LISTED = 60;
	/** check_motion's look at each part over its longest cycle: this many poses, and this many solid blocks listed. */
	public static final int CHECK_MOTION_SAMPLES = 32;
	public static final int CHECK_MOTION_LISTED = 8;
	/** A build's redstone may be no nearer than this to something that reacts to power and isn't the build's (spec v2 §5.5). */
	public static final int MACHINE_POWER_REACH = 2;
	/** A piston's push reads up to 12 blocks out and a sticky block's neighbours: the chunks this far round it must be loaded. */
	public static final int PISTON_READ_REACH = 14;
	/** How long a build's machine waits to start while a player stands where one of its pistons would push (30 s). */
	public static final int MACHINE_PLAYER_WAIT_TICKS = 600;
	/** How long undo lets a build's machine settle after putting its levers back, before taking the blocks back. */
	public static final int UNDO_SETTLE_TICKS = 40;
	/** check_build's look over the build (read over ticks), after terraform's wait for placement: inside the MCP timeout. */
	public static final int CHECK_BUILD_TIMEOUT_SECONDS = 60;
	/** How long check_build waits for the light engine to catch up with what was placed before it reads light. */
	public static final int CHECK_BUILD_LIGHT_WAIT_SECONDS = 5;
	/** check_build's "indoors": sky light at most this (open sky is 15, under an overhang in the open 14). */
	public static final int CHECK_BUILD_INDOOR_SKY_LIGHT = 13;
	/** Without a sky (the Nether, its ceiling over every spot), a spot is indoors under a block at most this far above it. */
	public static final int CHECK_ROOF_SCAN = 16;
	/** How long undo waits for a far build's item frames to load (entities load after their chunk) before going on. */
	public static final int UNDO_ENTITY_WAIT_SECONDS = 10;
	/** Placement feel (spec §9): blocks a tick that get a sound and a puff of particles; how loud next to a player's; how many particles. */
	public static final int FEEL_PER_TICK = 2;
	public static final float FEEL_VOLUME = 0.5f;
	public static final int FEEL_PARTICLES = 4;

	/** Moving parts (spec v2 §4): a new pose every this many ticks, which the game glides over in as many. */
	public static final int ANIMATION_STEP_TICKS = 4;
	/** A motion's cycle, in ticks (a spin may be negative to turn the other way). */
	public static final int MOTION_PERIOD_MIN = 10;
	public static final int MOTION_PERIOD_MAX = 2400;
	/** A swing's angles, in degrees either way. */
	public static final double MOTION_ANGLE_MAX = 180;
	/** How far a bob goes, in blocks. */
	public static final double MOTION_DISTANCE_MAX = 16;
	/** The game glides the short way between two poses: a motion must turn less than this between them. */
	public static final double STEP_DEGREES_MAX = 180;
	/**
	 * A part turning more than this in a step is posed more often (every 2 ticks, or every tick): between poses a
	 * display's corner glides straight, so a part turning further would draw in visibly (by 3% at 30°).
	 */
	public static final double SMOOTH_TURN_DEGREES = 30;
	public static final int MOTIONS_PER_PART_MAX = 8;
	public static final int PARTS_PER_BUILD_MAX = 32;
	/** How many parts deep a part may ride (a wing on a body on a ship). */
	public static final int PART_DEPTH_MAX = 4;
	/** A part's pivot may be this far outside the allowed box (a dragon circling a tower beside it). */
	public static final int PIVOT_REACH = 16;
	/** Displays one build may make by default (max_moving_blocks), and at most: each is an entity, drawn and sent. */
	public static final int MAX_MOVING_BLOCKS = 2000;
	public static final int MAX_MOVING_BLOCKS_CEILING = 10_000;
	/** How far a moving part's displays are drawn, in units of 64 blocks: about 256, past most render distances. */
	public static final float DISPLAY_VIEW_RANGE = 4;

	private Tunables() {
	}
}
