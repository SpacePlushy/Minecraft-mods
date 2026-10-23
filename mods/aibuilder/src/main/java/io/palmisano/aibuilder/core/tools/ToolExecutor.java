package io.palmisano.aibuilder.core.tools;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.palmisano.aibuilder.core.gadgets.Gadget;
import io.palmisano.aibuilder.core.gadgets.GadgetPlacement;
import io.palmisano.aibuilder.core.gadgets.GadgetUse;
import io.palmisano.aibuilder.core.gadgets.Gadgets;
import io.palmisano.aibuilder.core.gadgets.Port;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.anim.Motion;
import io.palmisano.aibuilder.core.anim.MotionCheck;
import io.palmisano.aibuilder.core.anim.MovingPart;
import io.palmisano.aibuilder.core.anim.Vec3;
import io.palmisano.aibuilder.core.blocks.BlockEntityData;
import io.palmisano.aibuilder.core.blocks.Footprint;
import io.palmisano.aibuilder.core.blocks.BlockInfo;
import io.palmisano.aibuilder.core.blocks.BlockSearch;
import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.blocks.RejectReason;
import io.palmisano.aibuilder.core.blocks.ResolvedBlock;
import io.palmisano.aibuilder.core.blocks.Validation;
import io.palmisano.aibuilder.core.blocks.Validator;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.FireRule;
import io.palmisano.aibuilder.core.place.Placement;
import io.palmisano.aibuilder.core.place.ProtectionMask;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.ArchStyle;
import io.palmisano.aibuilder.core.shapes.Axis;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.site.SiteSquare;
import io.palmisano.aibuilder.core.shapes.FillMode;
import io.palmisano.aibuilder.core.shapes.LocalBlock;
import io.palmisano.aibuilder.core.shapes.RoofStyle;
import io.palmisano.aibuilder.core.shapes.Roofs;
import io.palmisano.aibuilder.core.shapes.Shapes;
import io.palmisano.aibuilder.core.shapes.Staircases;
import io.palmisano.aibuilder.core.survey.BuildProblems;
import io.palmisano.aibuilder.core.survey.Column;
import io.palmisano.aibuilder.core.survey.RegionEncoder;
import io.palmisano.aibuilder.core.survey.SurveyEncoder;
import io.palmisano.aibuilder.core.survey.TerrainSnapshot;

/**
 * Runs Claude's tools (spec §8). The read tools look through the {@link WorldReader}; the write tools expand each
 * shape, validate its blocks, drop positions outside the allowed box, map the rest to the world through the frame and
 * queue them as one batch. A bad entry never fails the whole call.
 */
public final class ToolExecutor {
	private static final Set<String> TOOLS = Set.of("get_context", "survey_area", "inspect_region", "search_blocks", "submit_brief",
			"set_blocks", "fill", "line", "wall", "cylinder", "sphere", "cone", "arch", "roof", "staircase", "clear", "terraform",
			"place_block_entity", "checkpoint", "check_build", "animate", "check_motion", "test_redstone", "place_gadget", "finish");
	/** The write tools that can make a moving part (spec v2 §4.1), and clear, which takes a part's blocks out. */
	private static final Set<String> MOVABLE = Set.of("set_blocks", "fill", "line", "wall", "cylinder", "sphere", "cone", "arch", "roof",
			"staircase", "clear");
	private static final int REJECTION_LINES = 10;
	private static final BlockSpec AIR = BlockSpec.of("minecraft:air");
	private static final String BRIEF_FIRST = "Call submit_brief first: title, style, size, palette and phases, before placing any block.";
	// Spec §9: lava lights fires even when it doesn't flow. One wording each, so a shape's rejections group.
	private static final String LAVA_BY_FLAMMABLE = RejectReason.LAVA_NEAR_FLAMMABLE.wireName()
			+ ": something that burns (wood, leaves, grass, wool…) is within 3 blocks sideways, from 1 below to 4 above, and lava would set it"
			+ " alight; clear it first or keep the lava farther away";
	private static final String FLAMMABLE_BY_LAVA = RejectReason.LAVA_NEAR_FLAMMABLE.wireName()
			+ ": lava is within 3 blocks sideways, from 4 below to 1 above, and would set it alight; build it farther from the lava, or in"
			+ " something that doesn't burn";

	private final Validator validator;
	private final LocalFrame frame;
	private final Box allowed;
	private final SizeTier tier;
	private final long maxChangedBlocks;
	private final BuildSink sink;
	private final Budget budget;
	/** The gadgets this build placed (spec v2 §5.3), and the session's earlier builds' ones. Guarded by the executor. */
	private final List<GadgetUse> gadgets = new ArrayList<>();
	private final List<GadgetUse> earlierGadgets = new ArrayList<>();
	/** Which of its session's builds this is: 1 for a new build. */
	private int build = 1;
	private final WorldReader world;
	private ProtectionMask protection;
	private FireRule fire;
	private boolean briefed;
	private int movingBudget = Tunables.MAX_MOVING_BLOCKS;
	/** The build's moving parts, by name, in the order they were begun. */
	private final Map<String, PartState> parts = new LinkedHashMap<>();
	private int siteGap = Tunables.SITE_GAP;
	/** The build a refinement changes, and its changes so far; "" and null for a new build. */
	private String refining = "";
	private Box changes;
	private volatile boolean finished;
	private volatile CallListener listener = (name, args, result, millis) -> {
	};

	/** Told about every call once it's answered (the build log), on the calling thread: quick and thread-safe. */
	@FunctionalInterface
	public interface CallListener {
		/** {@code args} as Claude sent them (null for none); {@code millis}, how long the call took, waits included. */
		void called(String name, JsonObject args, ToolResult result, long millis);
	}

	public ToolExecutor(Validator validator, LocalFrame frame, Box allowed, SizeTier tier, long maxChangedBlocks, BuildSink sink, Budget budget,
			WorldReader world) {
		this.validator = validator;
		this.frame = frame;
		this.allowed = allowed;
		this.tier = tier;
		this.maxChangedBlocks = maxChangedBlocks;
		this.sink = sink;
		this.budget = budget;
		this.world = world;
	}

	public static Set<String> handledTools() {
		return TOOLS;
	}

	public boolean finished() {
		return finished;
	}

	/** The build's caps; the Claude Code backend adds output tokens to it as they stream in. */
	public Budget budget() {
		return budget;
	}

	/** The displays this build may make (the config's max_moving_blocks). */
	public synchronized void movingBudget(int blocks) {
		movingBudget = blocks;
	}

	/**
	 * A refinement's earlier moving parts (spec v2 §4.4): get_context lists them, animate may give one another motion,
	 * and a write naming one adds to it (its blocks count against max_moving_blocks).
	 */
	/**
	 * A refinement's session: the gadgets its earlier builds placed (a new one keeps a block from them), and which of the
	 * session's builds this one is (the gadgets it places are kept as that build's).
	 */
	public synchronized void refiningGadgets(List<GadgetUse> earlier, int build) {
		earlierGadgets.addAll(earlier);
		this.build = build;
	}

	public synchronized void refiningParts(Map<String, PartInfo> earlier) {
		earlier.forEach((name, info) -> {
			PartState state = parts.computeIfAbsent(name, n -> new PartState());
			state.blocks.addAll(info.blocks());
			state.motion = info.motion();
		});
	}

	/** The build's moving parts: each one's displays (local positions) and its motion (null until animated). */
	public synchronized Map<String, PartInfo> parts() {
		Map<String, PartInfo> out = new LinkedHashMap<>();
		parts.forEach((name, state) -> out.put(name, new PartInfo(List.copyOf(state.blocks), state.motion)));
		return out;
	}

	/** How far in front of the player a new build's origin is (the config's site_gap), for get_context. */
	public synchronized void siteGap(int gap) {
		siteGap = gap;
	}

	/** A refinement (spec §8): get_context names the build and the box its changes fill so far. */
	public synchronized void refining(String title, Box changes) {
		refining = title;
		this.changes = changes;
	}

	public void listen(CallListener listener) {
		this.listener = listener;
	}

	/**
	 * One tool call; thread-safe. Claude Code may send a turn's calls at once, so the ones that wait (a checkpoint for
	 * placement, the read tools for the world, terraform for both) wait outside the lock: they read only what never
	 * changes or what's thread-safe, and the writes that change the executor's state take the lock (M4 review).
	 */
	public ToolResult call(String name, JsonObject args) {
		long start = System.nanoTime();
		ToolResult result = answer(name, args);
		try {
			listener.called(name, args, result, (System.nanoTime() - start) / 1_000_000);
		} catch (RuntimeException e) {
			// A log that fails never fails the build.
		}
		return result;
	}

	private ToolResult answer(String name, JsonObject args) {
		ToolResult refusal = admit(name);
		if (refusal != null) {
			return withFooter(refusal);
		}
		ToolResult result;
		try {
			Args a = new Args(args);
			result = switch (name) {
				// Looking around needs no brief: it's what the brief is made from.
				case "get_context" -> context();
				case "survey_area" -> survey(a);
				case "inspect_region" -> inspect(a);
				case "search_blocks" -> search(a);
				case "checkpoint" -> checkpoint(a);
				case "check_build" -> checkBuild();
				case "check_motion" -> checkMotion();
				case "test_redstone" -> testRedstone(a);
				case "terraform" -> terraform(a);
				default -> locked(name, a);
			};
		} catch (IllegalArgumentException e) {
			result = ToolResult.error(RejectReason.BAD_ARGUMENT.wireName() + ": " + e.getMessage());
		} catch (BlockEntityData.Refused e) {
			result = ToolResult.error(e.reason().wireName() + ": " + e.getMessage());
		} catch (WorldReader.Unreadable e) {
			// The world couldn't be read in time (or at all): Claude can try again or go on without it.
			result = ToolResult.error("Couldn't read the world: " + e.getMessage());
		}
		return withFooter(result);
	}

	/** Counts the call; refuses it when the build is over or over budget (finish still goes through). */
	private synchronized ToolResult admit(String name) {
		budget.count();
		if (finished) {
			return ToolResult.error("This build is finished.");
		}
		if (budget.exhausted() && !name.equals("finish")) {
			return ToolResult.error("Budget reached: call finish now.");
		}
		return null;
	}

	/** The brief, finish and the writes: they change what later calls see. */
	private synchronized ToolResult locked(String name, Args a) {
		return switch (name) {
			case "submit_brief" -> brief(a);
			case "finish" -> finish(a);
			default -> {
				if (!TOOLS.contains(name)) {
					yield ToolResult.error("There is no tool called \"" + name + "\".");
				}
				if (!briefed) {
					yield ToolResult.error(BRIEF_FIRST);
				}
				yield write(name, a);
			}
		};
	}

	private ToolResult withFooter(ToolResult result) {
		String footer = budget.footer();
		return footer.isEmpty() ? result : new ToolResult(result.text() + "\n" + footer, result.isError(), result.rejections());
	}

	private ToolResult context() {
		WorldFacts facts = world.facts();
		int left = Math.max(0, budget.max() - budget.used());
		List<String> lines = new ArrayList<>();
		lines.add("Frame: you build in a local frame. +x is the player's right, +z is forward (away from the player), +y is up; y=0 is the ground"
				+ " at the origin, " + (refining.isEmpty() ? siteGap + " blocks in front of the player." : "where this build began.")
				+ " Block states take forward, back, left, right, up and down for directions, never compass names.");
		lines.add("Size: " + tier.label() + " (" + tier.size() + " blocks): the allowed box is " + describe(allowed) + "; at most "
				+ String.format(Locale.ROOT, "%,d", maxChangedBlocks) + " changed blocks.");
		if (!refining.isEmpty()) {
			lines.add("Refining: " + refining + ", the build this session made: its blocks fill " + describe(changes) + "; the allowed box"
					+ " reaches past them, and they're yours to change.");
		}
		Map<String, PartInfo> moving = parts();
		if (!moving.isEmpty()) {
			List<String> each = new ArrayList<>();
			moving.forEach((name, part) -> each.add(name + " (" + blocks(part.blocks().size()) + ") "
					+ (part.motion() == null ? "stands still" : motion(part.motion()))));
			lines.add("Moving parts: " + String.join("; ", each) + ". Use animate to give one another motion; a write naming one adds to it.");
		}
		lines.addAll(List.of(
				"World: " + facts.dimension() + ", biome " + facts.biome() + ", " + facts.time() + ", " + facts.weather()
						+ "; the ground block at the origin is " + facts.groundBlock() + ".",
				whereThePlayerIs(facts),
				"Budget: " + left + " of " + budget.max() + " tool calls left."));
		return ToolResult.ok(String.join("\n", lines));
	}

	/** Centred on the allowed box's footprint; the default radius covers it with 8 blocks to spare, as the site does. */
	private ToolResult survey(Args a) {
		int fits = SiteSquare.around(allowed, tier, Tunables.SITE_MARGIN).radius();
		int radius = Math.max(1, Math.min(a.optInt("radius", fits), Tunables.SURVEY_RADIUS_MAX));
		int centreX = Math.floorDiv(allowed.min().x() + allowed.max().x() + 1, 2);
		int centreZ = Math.floorDiv(allowed.min().z() + allowed.max().z() + 1, 2);
		TerrainSnapshot snapshot = world.terrain(centreX - radius, centreZ - radius, 2 * radius + 1, 2 * radius + 1);
		// The site is read once, as the build begins (it decides what's protected); a refinement's player may stand in it.
		String note = "As the site was when this build began: blocks it has placed since aren't in it (inspect_region shows blocks as they"
				+ " are now). " + whereThePlayerIs(world.facts());
		return ToolResult.ok(note + "\n" + SurveyEncoder.encode(snapshot, allowed, Tunables.SURVEY_MAX_CHARS - note.length() - 1));
	}

	/** Where the player is, in the frame (spec §5.5); nowhere when they're offline or in another dimension. */
	private static String whereThePlayerIs(WorldFacts facts) {
		Pos p = facts.player();
		return p == null ? "The player isn't in this world right now." : "The player stands at x " + p.x() + ", y " + p.y() + ", z " + p.z() + ".";
	}

	private ToolResult inspect(Args a) {
		Box box = a.box("box");
		// Far from the site the server would load or even generate chunks on the spot, and freeze while it did.
		Box readable = Box.of(allowed.min().x() - Tunables.SURVEY_RADIUS_MAX, Integer.MIN_VALUE / 2, allowed.min().z() - Tunables.SURVEY_RADIUS_MAX,
				allowed.max().x() + Tunables.SURVEY_RADIUS_MAX, Integer.MAX_VALUE / 2, allowed.max().z() + Tunables.SURVEY_RADIUS_MAX);
		if (!readable.contains(box.min()) || !readable.contains(box.max())) {
			return ToolResult.error(RejectReason.OUT_OF_BOUNDS.wireName() + ": inspect_region looks at most " + Tunables.SURVEY_RADIUS_MAX
					+ " blocks beyond the allowed box (x " + readable.min().x() + ".." + readable.max().x() + ", z " + readable.min().z() + ".."
					+ readable.max().z() + ")");
		}
		if (box.volume() > Tunables.INSPECT_MAX_BLOCKS) {
			return ToolResult.error(RejectReason.TOO_MANY.wireName() + ": inspect_region reads at most "
					+ String.format(Locale.ROOT, "%,d", Tunables.INSPECT_MAX_BLOCKS) + " blocks a call; this box holds " + blocks(box.volume())
					+ ". Look at it in parts.");
		}
		String text = RegionEncoder.encode(box, world.states(box));
		if (text.length() > Tunables.INSPECT_MAX_CHARS) {
			return ToolResult.error(RejectReason.TOO_MANY.wireName() + ": this box's text would run to " + String.format(Locale.ROOT, "%,d", text.length())
					+ " characters, past the " + String.format(Locale.ROOT, "%,d", Tunables.INSPECT_MAX_CHARS) + " one read returns (a line a row, so"
					+ " a tall or deep, narrow box is the longest); look at it in parts, wider and flatter");
		}
		return ToolResult.ok(text);
	}

	private ToolResult search(Args a) {
		String query = a.string("query");
		int limit = Math.max(1, Math.min(a.optInt("limit", Tunables.SEARCH_LIMIT_DEFAULT), Tunables.SEARCH_LIMIT_MAX));
		List<BlockInfo> found = BlockSearch.search(validator.catalog(), query, limit);
		if (found.isEmpty()) {
			return ToolResult.ok("Nothing matches \"" + query + "\".");
		}
		StringBuilder out = new StringBuilder(blocks(found.size())).append(" for \"").append(query)
				.append("\" (each with its properties and values; directions are relative):");
		for (BlockInfo info : found) {
			out.append('\n').append(BlockSearch.describe(info)).append(validator.isBanned(info) ? " (can't be placed)" : "");
		}
		return ToolResult.ok(out.toString());
	}

	/**
	 * Levels the box's footprint (inside the allowed box) to {@code target_y}, from the ground as it is now: what's
	 * queued is placed first, then the footprint is read again, so a second terraform over the same ground (a terrace,
	 * a plinth) builds on the first. Above the target becomes air, up to the column's highest block (so trees go too);
	 * below, the solid floor (under any water or lava) is filled up with {@code fill_block}; {@code target_y} gets
	 * {@code surface_block}. Nothing outside the allowed box's height.
	 */
	private ToolResult terraform(Args a) {
		synchronized (this) {
			if (!briefed) {
				return ToolResult.error(BRIEF_FIRST);
			}
		}
		// Outside the box's height it couldn't place its surface anyway, and a far target would mean millions of blocks.
		int target = a.integer("target_y");
		if (target < allowed.min().y() || target > allowed.max().y()) {
			return ToolResult.error(RejectReason.OUT_OF_BOUNDS.wireName() + ": target_y " + target + " is outside the allowed box's height, y "
					+ allowed.min().y() + ".." + allowed.max().y() + "; nothing was queued");
		}
		List<LocalBlock> blocks = terraform(a, target);
		synchronized (this) {
			return queue(blocks, false, false, "");
		}
	}

	/** The waits (for what's queued, then for the ground) happen outside the lock, within the MCP timeout together. */
	private List<LocalBlock> terraform(Args a, int target) {
		Box box = a.box("box");
		BlockSpec surface = BlockSpec.of(a.string("surface_block"));
		BlockSpec fill = BlockSpec.of(a.optString("fill_block", "minecraft:dirt"));
		int minX = Math.max(box.min().x(), allowed.min().x());
		int maxX = Math.min(box.max().x(), allowed.max().x());
		int minZ = Math.max(box.min().z(), allowed.min().z());
		int maxZ = Math.min(box.max().z(), allowed.max().z());
		if (minX > maxX || minZ > maxZ) {
			return List.of();
		}
		sink.awaitPlaced();
		TerrainSnapshot terrain = world.currentTerrain(minX, minZ, maxX - minX + 1, maxZ - minZ + 1);
		List<LocalBlock> out = new ArrayList<>();
		for (int z = minZ; z <= maxZ; z++) {
			for (int x = minX; x <= maxX; x++) {
				Column column = terrain.at(x, z);
				// Over a drop with no floor in reach, the fill comes up from the box's bottom.
				boolean drop = column.floor() == Column.NONE;
				int solid = drop ? allowed.min().y() - 1 : column.floor() - column.water() - column.lava();
				for (int y = Math.max(solid + 1, allowed.min().y()); y < target; y++) {
					out.add(new LocalBlock(new Pos(x, y, z), fill));
				}
				out.add(new LocalBlock(new Pos(x, target, z), surface));
				int top = drop ? target : Math.min(Math.max(column.top(), column.floor()), allowed.max().y());
				for (int y = target + 1; y <= top; y++) {
					out.add(new LocalBlock(new Pos(x, y, z), AIR));
				}
			}
		}
		return out;
	}

	private ToolResult brief(Args a) {
		Map<String, String> palette = new LinkedHashMap<>();
		if (a.has("palette")) {
			for (JsonElement e : a.array("palette")) {
				Args entry = new Args(e.isJsonObject() ? e.getAsJsonObject() : null);
				palette.put(entry.string("role"), entry.string("block"));
			}
		}
		List<String> phases = a.has("phases") ? a.strings("phases") : List.of();
		Args size = new Args(a.has("size") ? a.object("size") : null);
		String title = a.string("title");
		if (title.codePointCount(0, title.length()) > Tunables.TITLE_MAX) {
			// It names the build in chat, on the panel and on the screen's mode line (a packet of at most 256 characters).
			title = title.substring(0, title.offsetByCodePoints(0, Tunables.TITLE_MAX - 1)).strip() + "…";
		}
		Brief brief = new Brief(title, a.optString("style", ""), size.optInt("w", 0), size.optInt("h", 0), size.optInt("d", 0), palette, phases);
		sink.brief(brief);
		briefed = true;
		return ToolResult.ok("Brief noted: " + brief.title() + ".");
	}

	private ToolResult checkpoint(Args a) {
		String phase = a.string("phase");
		int placed = sink.checkpoint(phase);
		long waiting = sink.pending();
		// The checkpoint answers within the MCP timeout even when placement is behind.
		return ToolResult.ok("Checkpoint " + phase + ": " + blocks(placed) + " placed so far."
				+ (waiting > 0 ? " " + String.format(Locale.ROOT, "%,d", waiting) + " blocks are still going in; call checkpoint again to wait for them." : ""));
	}

	/**
	 * Waits for what's queued (as terraform does), then has the build looked over in the world (spec §8). The waits
	 * are outside the lock, within the MCP timeout together.
	 */
	private ToolResult checkBuild() {
		synchronized (this) {
			if (!briefed) {
				return ToolResult.error(BRIEF_FIRST);
			}
		}
		ToolResult waiting = placedFirst("check_build");
		return waiting != null ? waiting : ToolResult.ok(BuildProblems.encode(world.check(), Tunables.CHECK_BUILD_LISTED));
	}

	/**
	 * Waits for what's queued, then looks over the moving parts (spec v2 §4.5): where each goes over its cycle, and
	 * the solid blocks, the build's or the world's, it would pass through. The waits are outside the lock.
	 */
	private ToolResult checkMotion() {
		Map<String, List<Pos>> blocks = new LinkedHashMap<>();
		Map<String, MovingPart> motions = new HashMap<>();
		synchronized (this) {
			parts.forEach((name, state) -> {
				blocks.put(name, List.copyOf(state.blocks));
				if (state.motion != null) {
					motions.put(name, state.motion);
				}
			});
		}
		if (blocks.isEmpty()) {
			return ToolResult.ok(MotionCheck.report(blocks, motions, frame.facing(), asked -> Set.of()));
		}
		ToolResult waiting = placedFirst("check_motion");
		return waiting != null ? waiting : ToolResult.ok(MotionCheck.report(blocks, motions, frame.facing(), world::solid));
	}

	/** Tries the build's machine in the world (spec v2 §5.2), once what's queued is in. */
	private ToolResult testRedstone(Args a) {
		List<RedstoneSteps.Step> steps = RedstoneSteps.parse(a.array("steps"), allowed);
		ToolResult waiting = placedFirst("test_redstone");
		return waiting != null ? waiting : ToolResult.ok(world.testRedstone(steps));
	}

	/** Waits for what's queued; null once it's all in, else the answer that blocks are still going in (half a build looks half right). */
	private ToolResult placedFirst(String tool) {
		sink.awaitPlaced();
		long waiting = sink.pending();
		return waiting == 0 ? null : ToolResult.ok(String.format(Locale.ROOT, "%,d", waiting) + " blocks are still going in; call checkpoint to"
				+ " wait for them, then " + tool + " again.");
	}

	private ToolResult finish(Args a) {
		finished = true;
		sink.finish(a.optString("summary", ""));
		return ToolResult.ok("Build finished.");
	}

	private ToolResult write(String name, Args a) {
		if (name.equals("animate")) {
			return animate(a);
		}
		if (name.equals("place_gadget")) {
			return placeGadget(a);
		}
		String part = a.has("part") ? partName(a.string("part")) : "";
		if (!part.isEmpty() && !MOVABLE.contains(name)) {
			return ToolResult.error(RejectReason.BAD_ARGUMENT.wireName() + ": " + name + " can't make a moving part");
		}
		if (!part.isEmpty() && name.equals("fill") && a.optString("mode", "solid").equalsIgnoreCase("replace")) {
			return ToolResult.error(RejectReason.BAD_ARGUMENT.wireName() + ": a moving part can't be a replace fill: it changes no block, so"
					+ " there's nothing for it to replace");
		}
		if (!part.isEmpty() && !parts.containsKey(part) && parts.size() >= Tunables.PARTS_PER_BUILD_MAX) {
			return ToolResult.error(RejectReason.TOO_MANY.wireName() + ": a build has at most " + Tunables.PARTS_PER_BUILD_MAX + " moving parts");
		}
		if (name.equals("set_blocks")) {
			return setBlocks(a, part);
		}
		if (name.equals("place_block_entity")) {
			return placeBlockEntity(a);
		}
		// A shape bigger than the whole allowed box is a mistake; refuse it before building it in memory.
		long size = upperBound(name, a);
		if (size > allowed.volume()) {
			return ToolResult.error(RejectReason.TOO_MANY.wireName() + ": this " + name + " covers up to " + blocks(size)
					+ ", more than the whole allowed box (" + describe(allowed) + ") holds; nothing was queued");
		}
		List<LocalBlock> blocks = switch (name) {
			case "fill" -> fill(a);
			case "clear" -> Shapes.fill(a.box("box"), AIR, FillMode.SOLID);
			case "line" -> Shapes.line(a.pos("from"), a.pos("to"), a.block("block"));
			case "wall" -> Shapes.wall(a.points("points"), a.integer("y"), a.integer("height"), a.block("block"), a.optInt("thickness", 1));
			case "cylinder" -> Shapes.cylinder(a.pos("center"), a.integer("radius"), a.integer("height"), a.block("block"),
					axis(a.optString("axis", "y")), a.optBool("hollow", false));
			case "sphere" -> Shapes.sphere(a.pos("center"), a.integer("radius"), a.block("block"), a.optBool("hollow", false),
					a.optBool("dome", false));
			case "cone" -> Shapes.cone(a.pos("base_center"), a.integer("radius"), a.integer("height"), a.block("block"),
					a.optBool("hollow", false));
			case "arch" -> Shapes.arch(a.pos("from"), a.pos("to"), a.integer("rise"), a.block("block"), a.optInt("thickness", 1),
					enumOf(ArchStyle.class, a.optString("style", "round"), "style"));
			case "roof" -> Roofs.roof(a.box("box"), enumOf(RoofStyle.class, a.string("style"), "style"), BlockSpec.of(a.string("stairs")),
					a.has("slab") ? BlockSpec.of(a.string("slab")) : null, a.has("ridge") ? axis(a.string("ridge")) : null,
					a.optInt("overhang", 0), a.has("gable_block") ? BlockSpec.of(a.string("gable_block")) : null);
			case "staircase" -> staircase(a);
			default -> throw new IllegalStateException(name);
		};
		return queue(blocks, false, name.equals("clear") && a.optBool("include_protected", false), part);
	}

	/**
	 * Places a gadget from the library (spec v2 §5.3), whole or not at all: its box must be inside the allowed box, hold
	 * nothing player-made or unbreakable, and keep a block from every other gadget of the session (their redstone mustn't
	 * touch); its ports' spots must be inside the box too. It writes its blocks and air in the rest of its box (what
	 * stood there could touch its redstone), through the queue as any write.
	 */
	private ToolResult placeGadget(Args a) {
		String kind = a.string("kind");
		Pos at = a.pos("at");
		String facing = a.optString("facing", "forward");
		JsonObject options = a.has("options") ? a.object("options") : new JsonObject();
		Gadget gadget = Gadgets.make(kind, options);
		GadgetPlacement.Placed placed = GadgetPlacement.place(gadget, at, facing);
		Box box = placed.box();
		String what = Gadgets.describe(kind, options);
		if (!allowed.contains(box.min()) || !allowed.contains(box.max())) {
			return ToolResult.error(RejectReason.OUT_OF_BOUNDS.wireName() + ": " + what + " there would fill " + describe(box) + ", past the allowed box "
					+ describe(allowed) + "; nothing was placed");
		}
		for (Port port : placed.ports()) {
			if (!allowed.contains(port.at())) {
				return ToolResult.error(RejectReason.OUT_OF_BOUNDS.wireName() + ": " + what + "'s port " + port.name() + " would be at " + spot(port.at())
						+ ", past the allowed box " + describe(allowed) + ", where no wire can reach it; nothing was placed");
			}
		}
		List<GadgetUse> others = new ArrayList<>(earlierGadgets);
		others.addAll(gadgets);
		for (GadgetUse other : others) {
			Box near = other.placed().box();
			if (box.max().x() >= near.min().x() - 1 && box.min().x() <= near.max().x() + 1 && box.max().y() >= near.min().y() - 1
					&& box.min().y() <= near.max().y() + 1 && box.max().z() >= near.min().z() - 1 && box.min().z() <= near.max().z() + 1) {
				return ToolResult.error("too_close: " + what + " there would fill " + describe(box) + ", touching the " + other.kind() + " at "
						+ describe(near) + ": leave a block between gadgets (their redstone mustn't touch); nothing was placed");
			}
		}
		ProtectionMask mask = protection();
		Set<Pos> filled = new HashSet<>();
		placed.blocks().forEach(b -> filled.add(b.pos()));
		List<LocalBlock> blocks = new ArrayList<>(placed.blocks());
		for (int x = box.min().x(); x <= box.max().x(); x++) {
			for (int y = box.min().y(); y <= box.max().y(); y++) {
				for (int z = box.min().z(); z <= box.max().z(); z++) {
					Pos p = new Pos(x, y, z);
					if (mask.isProtected(p) || mask.isUnbreakable(p)) {
						return ToolResult.error(RejectReason.PROTECTED.wireName() + ": something player-made (or unbreakable) is at " + spot(p) + ", inside where "
								+ what + " would go; place it elsewhere; nothing was placed");
					}
					if (!filled.contains(p)) {
						blocks.add(new LocalBlock(p, AIR));
					}
				}
			}
		}
		ToolResult queued = queue(blocks, false, false, "");
		if (queued.isError()) {
			return queued;
		}
		GadgetUse use = new GadgetUse(kind, options.toString(), at, facing.toLowerCase(Locale.ROOT), build);
		gadgets.add(use);
		sink.gadget(use);
		StringBuilder answer = new StringBuilder("Placed ").append(what).append(" facing ").append(use.facing()).append(", its front-left-bottom block at ")
				.append(spot(at)).append(": it fills ").append(describe(box)).append(", ").append(String.format(Locale.ROOT, "%,d", placed.blocks().size()))
				.append(" blocks.");
		List<String> in = placed.ports().stream().filter(Port::input).map(p -> p.name() + " " + spot(p.at())).toList();
		List<String> out = placed.ports().stream().filter(p -> !p.input()).map(p -> p.name() + " " + spot(p.at())).toList();
		if (!in.isEmpty()) {
			answer.append("\nInputs (a wire's last dust goes on the spot): ").append(String.join(", ", in)).append('.');
		}
		if (!out.isEmpty()) {
			answer.append("\nOutputs (a wire's first dust goes on the spot): ").append(String.join(", ", out)).append('.');
		}
		if (!placed.marks().isEmpty()) {
			List<String> marks = new ArrayList<>();
			placed.marks().forEach((name, p) -> marks.add(name + " " + spot(p)));
			answer.append("\nTo test it: ").append(String.join(", ", marks)).append('.');
		}
		answer.append("\nIt settles within ").append(gadget.settleTicks()).append(" ticks of a change at its inputs.");
		return ToolResult.ok(answer.toString());
	}

	private static String spot(Pos p) {
		return "(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
	}

	/** A part's name: a short word, as Claude will use it again in animate and in later builds. */
	private static String partName(String name) {
		if (!name.matches("[a-z][a-z0-9_]{0,31}")) {
			throw new IllegalArgumentException("part must be a short name in lowercase letters, digits and _ (like left_wing), not \"" + name + "\"");
		}
		return name;
	}

	/**
	 * Gives a moving part its motion, replacing any it had (spec v2 §4.2): about a pivot within reach of the box,
	 * 1 to 8 motions, riding with a parent part if one is named (never itself, at most 4 deep).
	 */
	private ToolResult animate(Args a) {
		String name = a.string("part");
		PartState state = parts.get(name);
		if (state == null) {
			return ToolResult.error(RejectReason.BAD_ARGUMENT.wireName() + ": " + noPart(name));
		}
		double[] p = a.numbers("pivot", 3);
		Vec3 pivot = new Vec3(p[0], p[1], p[2]);
		int reach = Tunables.PIVOT_REACH;
		if (p[0] < allowed.min().x() - reach || p[0] > allowed.max().x() + reach || p[1] < allowed.min().y() - reach
				|| p[1] > allowed.max().y() + reach || p[2] < allowed.min().z() - reach || p[2] > allowed.max().z() + reach) {
			return ToolResult.error(RejectReason.BAD_ARGUMENT.wireName() + ": the pivot " + describe(pivot) + " is too far from the build: keep it"
					+ " within " + reach + " blocks of the allowed box " + describe(allowed));
		}
		JsonArray given = a.array("motions");
		if (given.isEmpty() || given.size() > Tunables.MOTIONS_PER_PART_MAX) {
			return ToolResult.error(RejectReason.BAD_ARGUMENT.wireName() + ": motions must be a list of 1 to " + Tunables.MOTIONS_PER_PART_MAX
					+ " motions");
		}
		List<Motion> motions = new ArrayList<>();
		for (JsonElement e : given) {
			motions.add(Motion.parse(e.isJsonObject() ? e.getAsJsonObject() : null));
		}
		String parent = a.optString("parent", "");
		if (!parent.isEmpty()) {
			if (!parts.containsKey(parent)) {
				return ToolResult.error(RejectReason.BAD_ARGUMENT.wireName() + ": " + noPart(parent));
			}
			// Up the chain from the parent: never back to this part, and at most PART_DEPTH_MAX parts in all.
			int depth = 1;
			for (String at = parent; !at.isEmpty(); at = parts.containsKey(at) && parts.get(at).motion != null ? parts.get(at).motion.parent() : "") {
				if (at.equals(name)) {
					return ToolResult.error(RejectReason.BAD_ARGUMENT.wireName() + ": " + name + " can't ride " + parent + ", which rides " + name
							+ ": a part can't ride itself");
				}
				if (++depth > Tunables.PART_DEPTH_MAX) {
					return ToolResult.error(RejectReason.BAD_ARGUMENT.wireName() + ": parts ride at most " + Tunables.PART_DEPTH_MAX + " deep");
				}
			}
		}
		MovingPart part = new MovingPart(name, pivot, motions, parent);
		state.motion = part;
		sink.animate(part);
		return ToolResult.ok(describe(part));
	}

	private static String noPart(String name) {
		return "there's no part called \"" + name + "\": build it first, with \"part\": \"" + name + "\" on a write tool";
	}

	/** A part's motion in words, as animate answers it. */
	static String describe(MovingPart part) {
		return part.name() + " " + motion(part) + ".";
	}

	/** "moves about the pivot …: swings about …", as animate answers and get_context lists it. */
	private static String motion(MovingPart part) {
		StringBuilder out = new StringBuilder("moves about the pivot ").append(describe(part.pivot())).append(": ");
		List<String> each = new ArrayList<>();
		for (Motion m : part.motions()) {
			each.add(switch (m) {
				case Motion.Swing s -> "swings about " + s.axis() + " from " + number(s.from()) + "° to " + number(s.to()) + "° every "
						+ s.period() + " ticks";
				case Motion.Spin s -> "spins about " + s.axis() + (s.period() < 0 ? " the other way" : "") + " once every " + Math.abs(s.period())
						+ " ticks";
				case Motion.Bob b -> "bobs " + b.direction() + " by up to " + number(b.distance()) + " blocks every " + b.period() + " ticks";
			} + (phaseOf(m) == 0 ? "" : " (phase " + number(phaseOf(m)) + ")"));
		}
		out.append(String.join(", then ", each));
		if (!part.parent().isEmpty()) {
			out.append("; it rides with ").append(part.parent());
		}
		return out.toString();
	}

	private static double phaseOf(Motion m) {
		return switch (m) {
			case Motion.Swing s -> s.phase();
			case Motion.Spin s -> s.phase();
			case Motion.Bob b -> b.phase();
		};
	}

	private static String number(double n) {
		return n == Math.rint(n) ? String.valueOf((long) n) : String.format(Locale.ROOT, "%.2f", n).replaceAll("0+$", "");
	}

	private static String describe(Vec3 p) {
		return "[" + number(p.x()) + ", " + number(p.y()) + ", " + number(p.z()) + "]";
	}

	/** One part's state as the build goes: its displays so far, and its motion. Guarded by the executor. */
	private static final class PartState {
		/** A spot given again replaces its block, as a block would: one display a spot. */
		final Set<Pos> blocks = new LinkedHashSet<>();
		MovingPart motion;
	}

	/**
	 * At most how many blocks a shape comes to, from its arguments alone. Generous (a sphere counts its whole cube);
	 * an argument the shape itself would refuse counts as 0, so the shape's own message reaches Claude.
	 */
	private static long upperBound(String name, Args a) {
		return switch (name) {
			case "fill", "clear" -> a.box("box").volume();
			case "line" -> span(a.pos("from"), a.pos("to")) + 1;
			case "wall" -> {
				List<int[]> points = a.points("points");
				long length = 1;
				for (int i = 1; i < points.size(); i++) {
					length += Math.max(Math.abs((long) points.get(i)[0] - points.get(i - 1)[0]),
							Math.abs((long) points.get(i)[1] - points.get(i - 1)[1])) + 1;
				}
				yield length * atLeast0(a.integer("height")) * atLeast0(a.optInt("thickness", 1));
			}
			case "cylinder", "cone" -> square(atLeast0(2L * a.integer("radius") + 1)) * atLeast0(a.integer("height"));
			case "sphere" -> square(atLeast0(2L * a.integer("radius") + 1)) * atLeast0(2L * a.integer("radius") + 1);
			case "arch" -> (span(a.pos("from"), a.pos("to")) + 1) * atLeast0(a.integer("rise") + 1L)
					* atLeast0(a.optInt("thickness", 1));
			case "roof" -> {
				Box box = a.box("box");
				long grow = 2L * atLeast0(a.optInt("overhang", 0));
				long width = box.max().x() - box.min().x() + 1 + grow;
				long depth = box.max().z() - box.min().z() + 1 + grow;
				yield width * depth * (Math.max(width, depth) + 1);
			}
			case "staircase" -> a.string("style").equalsIgnoreCase("spiral")
					? atLeast0(a.integer("to_y") - (long) a.integer("from_y") + 1) + 8 * atLeast0(a.integer("radius"))
					: atLeast0(a.integer("steps")) * atLeast0(a.optInt("width", 1));
			default -> 0;
		};
	}

	/** The longest of the three distances between two points. */
	private static long span(Pos from, Pos to) {
		return Math.max(Math.abs((long) to.x() - from.x()), Math.max(Math.abs((long) to.y() - from.y()), Math.abs((long) to.z() - from.z())));
	}

	private static long square(long n) {
		return n * n;
	}

	private static long atLeast0(long n) {
		return Math.max(0, n);
	}

	private static List<LocalBlock> fill(Args a) {
		String mode = a.optString("mode", "solid").toLowerCase(Locale.ROOT);
		if (mode.equals("replace")) {
			return Shapes.replace(a.box("box"), a.block("block"), a.string("filter"));
		}
		return Shapes.fill(a.box("box"), a.block("block"), enumOf(FillMode.class, mode, "mode"));
	}

	private static List<LocalBlock> staircase(Args a) {
		return switch (a.string("style").toLowerCase(Locale.ROOT)) {
			case "straight" -> Staircases.straight(a.pos("from"), a.string("direction").toLowerCase(Locale.ROOT), a.integer("steps"),
					a.optInt("width", 1),
					a.block("block"));
			case "spiral" -> Staircases.spiral(a.pos("center"), a.integer("radius"), a.integer("from_y"), a.integer("to_y"),
					a.block("block"), a.optBool("clockwise", true));
			default -> throw new IllegalArgumentException("style must be straight or spiral");
		};
	}

	private ToolResult setBlocks(Args a, String part) {
		var entries = a.array("blocks");
		if (entries.size() > Tunables.SET_BLOCKS_MAX) {
			return ToolResult.error(RejectReason.TOO_MANY.wireName() + ": set_blocks takes at most "
					+ String.format(Locale.ROOT, "%,d", Tunables.SET_BLOCKS_MAX) + " blocks per call; got "
					+ String.format(Locale.ROOT, "%,d", entries.size()));
		}
		List<LocalBlock> blocks = new ArrayList<>();
		for (JsonElement e : entries) {
			Args entry = new Args(e.isJsonObject() ? e.getAsJsonObject() : null);
			blocks.add(new LocalBlock(entry.pos("pos"), entry.block("block")));
		}
		return queue(blocks, true, false, part);
	}

	/**
	 * A sign, container, lectern or banner with its data, checked like any write, the data going in right after its
	 * block; or an item frame, an entity on the face of the block behind it (spec §8, §10).
	 */
	private ToolResult placeBlockEntity(Args a) {
		Pos pos = a.pos("pos");
		BlockEntityData data = BlockEntityData.parse(a.json(), world.items());
		if (data instanceof BlockEntityData.ItemFrame itemFrame) {
			return queueFrame(pos, itemFrame);
		}
		BlockSpec spec = a.block("block");
		Validation checked = validator.validate(spec);
		if (checked instanceof Validation.Valid valid && valid.block().info().id().equals("minecraft:dispenser")
				&& data instanceof BlockEntityData.Container container) {
			// A machine moves and harms nothing that isn't the build's (spec v2 §5.5): a dispenser holds only what it drops.
			for (BlockEntityData.Stack stack : container.items()) {
				if (world.items().dispenses(stack.item())) {
					return ToolResult.error(RejectReason.BAD_VALUE.wireName() + ": a dispenser holds only what it would simply drop, and it would use "
							+ stack.item() + " (pour it, fire it, place it or put it on), which could reach what isn't the build's; a dropper may"
							+ " hold it, as long as nothing of the build feeds a dispenser");
				}
			}
		}
		if (checked instanceof Validation.Valid valid && valid.block().info().entity() != data.kind()) {
			return ToolResult.error(RejectReason.NOT_A_BLOCK_ENTITY.wireName() + ": " + valid.block().info().id() + " can't hold "
					+ data.kind().name().toLowerCase(Locale.ROOT) + " data; use " + switch (data.kind()) {
						case SIGN -> "a sign (oak_sign, oak_wall_sign, oak_hanging_sign…)";
						case CONTAINER -> "storage that keeps its items (chest, trapped_chest, barrel, a shulker box, dispenser, dropper, decorated_pot)";
						case LECTERN -> "a lectern";
						default -> "a banner (white_banner, red_wall_banner…)";
					});
		}
		return queue(List.of(new LocalBlock(pos, spec, null, data)), true, false, "");
	}

	/** An entity, so no block changes: only the box, what's protected and the queue's limit apply. */
	private ToolResult queueFrame(Pos pos, BlockEntityData.ItemFrame itemFrame) {
		if (!allowed.contains(pos)) {
			return ToolResult.error(RejectReason.OUT_OF_BOUNDS.wireName() + ": " + describe(pos) + " is outside the allowed box " + describe(allowed));
		}
		if (protection().isProtected(pos) || protection().isUnbreakable(pos)) {
			return ToolResult.error(RejectReason.PROTECTED.wireName() + ": something player-made is there; hang the frame somewhere else");
		}
		long waiting = sink.pending();
		if (waiting + 1 > Tunables.PENDING_MAX) {
			return ToolResult.error(RejectReason.TOO_MANY.wireName() + ": " + String.format(Locale.ROOT, "%,d", waiting)
					+ " blocks are still waiting to be placed; call checkpoint, then go on; nothing was queued");
		}
		sink.queue(List.of(new Placement(frame.toWorld(pos), null, null, itemFrame)));
		return ToolResult.ok("Queued an item frame" + (itemFrame.item().isEmpty() ? "" : " holding " + itemFrame.item())
				+ ". It hangs on the block behind it, which must be solid by the time the frame goes in.");
	}

	/**
	 * Validates, bounds-checks, protection-checks, keeps lava apart from what burns, and queues; {@code perEntry}
	 * reports each rejected entry by its index; {@code includeProtected} (a clear that asked for it) may remove
	 * player-made blocks, never unbreakable ones.
	 */
	private ToolResult queue(List<LocalBlock> blocks, boolean perEntry, boolean includeProtected, String part) {
		// A moving part's displays change no block (spec v2 §4.1): not the changed-blocks cap, not the lava rule, no
		// claims on protected spots; the moving budget instead.
		boolean moving = !part.isEmpty();
		if (!moving && sink.capReached()) {
			return ToolResult.error(RejectReason.TOO_MANY.wireName() + ": this build has reached its limit of "
					+ String.format(Locale.ROOT, "%,d", maxChangedBlocks) + " changed blocks; call finish");
		}
		ProtectionMask mask = protection();
		Map<BlockSpec, Validation> checked = new HashMap<>();
		int[] fit = new int[blocks.size()];
		int fitCount = 0;
		Map<String, Integer> grouped = new LinkedHashMap<>();
		List<String> lines = new ArrayList<>();
		// Claude's entries for one spot: only the last given goes in and is judged. Placement sorts a batch (fluids
		// last), so an earlier lava would otherwise end up over a later block the lava rule took the spot for.
		boolean[] superseded = perEntry ? superseded(blocks) : null;
		int supersededCount = 0;
		for (int i = 0; i < blocks.size(); i++) {
			if (superseded != null && superseded[i]) {
				supersededCount++;
				continue;
			}
			LocalBlock block = blocks.get(i);
			Validation v = checked.computeIfAbsent(block.block(), validator::validate);
			String problem = null;
			if (v instanceof Validation.Invalid invalid) {
				problem = invalid.rejection().reason().wireName() + ": " + invalid.rejection().detail();
			} else if (!allowed.contains(block.pos())) {
				problem = RejectReason.OUT_OF_BOUNDS.wireName() + ": " + (perEntry ? describe(block.pos()) + " is " : "")
						+ "outside the allowed box " + describe(allowed);
			} else if (mask.isUnbreakable(block.pos())) {
				problem = RejectReason.PROTECTED.wireName() + ": it can't be broken (bedrock, a barrier…), so it stays";
			} else if (!includeProtected && mask.isProtected(block.pos())) {
				problem = RejectReason.PROTECTED.wireName()
						+ ": it looks player-made, so it stays: build around it (include_protected is only for when the player asked to remove it)";
			} else if (v instanceof Validation.Valid valid) {
				List<Pos> others = Footprint.others(valid.block(), block.pos());
				problem = !moving ? otherHalf(others, mask, perEntry, includeProtected)
						: others.isEmpty() ? null : RejectReason.BAD_ARGUMENT.wireName() + ": a door, bed or tall plant can't be in a moving part (it's two"
								+ " blocks); build it as blocks";
			}
			if (problem != null) {
				reject(lines, grouped, perEntry, i, problem);
				continue;
			}
			fit[fitCount++] = i;
		}
		// Far ahead of placement, the queue itself would fill the game's memory: refuse the call whole, before the
		// lava rule below remembers any of it.
		long waiting = sink.pending();
		if (fitCount > 0 && waiting + fitCount > Tunables.PENDING_MAX) {
			return ToolResult.error(RejectReason.TOO_MANY.wireName() + ": " + String.format(Locale.ROOT, "%,d", waiting)
					+ " blocks are still waiting to be placed; call checkpoint, then go on; nothing was queued");
		}
		if (moving) {
			int have = movingBlocks();
			Set<Pos> already = parts.containsKey(part) ? parts.get(part).blocks : Set.of();
			int adding = 0;
			for (int n = 0; n < fitCount; n++) {
				LocalBlock block = blocks.get(fit[n]);
				if (!air(((Validation.Valid) checked.get(block.block())).block()) && !already.contains(block.pos())) {
					adding++;
				}
			}
			if (have + adding > movingBudget) {
				return ToolResult.error(RejectReason.TOO_MANY.wireName() + ": this build may have " + String.format(Locale.ROOT, "%,d", movingBudget)
						+ " moving blocks (max_moving_blocks) and has " + String.format(Locale.ROOT, "%,d", have) + ", so "
						+ String.format(Locale.ROOT, "%,d", movingBudget - have) + " left; this would add " + String.format(Locale.ROOT, "%,d", adding)
						+ ". Nothing was queued");
			}
		}
		FireRule fire = moving ? null : fire();
		List<Placement> batch = new ArrayList<>(fitCount);
		List<Pos> claimed = new ArrayList<>(fitCount);
		// Air in a part takes that spot out of it (its display hides); where the part has nothing, there's nothing to do.
		int nothingToTakeOut = 0;
		for (int n = 0; n < fitCount; n++) {
			LocalBlock block = blocks.get(fit[n]);
			ResolvedBlock resolved = ((Validation.Valid) checked.get(block.block())).block();
			if (moving) {
				if (air(resolved)) {
					PartState state = parts.get(part);
					if (state == null || !state.blocks.remove(block.pos())) {
						nothingToTakeOut++;
						continue;
					}
				} else {
					parts.computeIfAbsent(part, p -> new PartState()).blocks.add(block.pos());
				}
				batch.add(new Placement(frame.toWorld(block.pos()), resolved, null, null, part));
				continue;
			}
			FireRule.Kind kind = FireRule.Kind.of(resolved.info());
			List<Pos> others = Footprint.others(resolved, block.pos());
			// A block of two goes in whole or not at all: both halves pass before either is remembered.
			if (!fire.allows(block.pos(), kind) || !others.stream().allMatch(other -> fire.allows(other, kind))) {
				reject(lines, grouped, perEntry, fit[n], kind == FireRule.Kind.LAVA ? LAVA_BY_FLAMMABLE : FLAMMABLE_BY_LAVA);
				continue;
			}
			fire.admit(block.pos(), kind, block.onlyReplacing() != null);
			// The other half is written whatever is there, even in replace mode.
			others.forEach(other -> fire.admit(other, kind, false));
			claimed.add(block.pos());
			claimed.addAll(others);
			batch.add(new Placement(frame.toWorld(block.pos()), resolved, block.onlyReplacing(), block.data()));
		}
		int rejected = blocks.size() - supersededCount - batch.size() - nothingToTakeOut;
		// Those spots are the build's own from now on, even where it may clear a protected block.
		claimed.forEach(mask::release);
		grouped.forEach((problem, count) -> lines.add(blocks(count) + " — " + problem));
		if (!batch.isEmpty()) {
			sink.queue(batch);
		}
		StringBuilder text = new StringBuilder("Queued ").append(blocks(batch.size()));
		if (moving) {
			text.append(" into the moving part ").append(part).append(" (").append(String.format(Locale.ROOT, "%,d", movingBlocks()))
					.append(" moving blocks in this build; ").append(String.format(Locale.ROOT, "%,d", movingBudget - movingBlocks())).append(" left)");
		}
		text.append('.');
		if (rejected > 0) {
			text.append(" Rejected ").append(String.format(Locale.ROOT, "%,d", rejected)).append(':');
			lines.stream().limit(REJECTION_LINES).forEach(line -> text.append('\n').append(line));
			if (lines.size() > REJECTION_LINES) {
				text.append("\n… and ").append(lines.size() - REJECTION_LINES).append(" more");
			}
		}
		return new ToolResult(text.toString(), batch.isEmpty() && rejected > 0, List.copyOf(lines));
	}

	private static boolean air(ResolvedBlock block) {
		return block.info().id().equals("minecraft:air");
	}

	private int movingBlocks() {
		return parts.values().stream().mapToInt(state -> state.blocks.size()).sum();
	}

	/** Which entries a later entry for the same spot replaces. */
	private static boolean[] superseded(List<LocalBlock> blocks) {
		boolean[] earlier = new boolean[blocks.size()];
		Map<Pos, Integer> last = new HashMap<>();
		for (int i = 0; i < blocks.size(); i++) {
			Integer before = last.put(blocks.get(i).pos(), i);
			if (before != null) {
				earlier[before] = true;
			}
		}
		return earlier;
	}

	/** Why a block's other half (a door's top, a bed's head) can't go where it would, or null if it can. */
	private String otherHalf(List<Pos> others, ProtectionMask mask, boolean perEntry, boolean includeProtected) {
		for (Pos other : others) {
			if (!allowed.contains(other)) {
				return RejectReason.OUT_OF_BOUNDS.wireName() + ": its other half" + (perEntry ? " at " + describe(other) : "")
						+ " would be outside the allowed box " + describe(allowed);
			}
			if (mask.isUnbreakable(other)) {
				return RejectReason.PROTECTED.wireName() + ": its other half would go where something can't be broken (bedrock, a barrier…)";
			}
			if (!includeProtected && mask.isProtected(other)) {
				return RejectReason.PROTECTED.wireName() + ": its other half would replace something that looks player-made: turn it or move it";
			}
		}
		return null;
	}

	private static void reject(List<String> lines, Map<String, Integer> grouped, boolean perEntry, int index, String problem) {
		if (perEntry) {
			lines.add("#" + index + " " + problem);
		} else {
			grouped.merge(problem, 1, Integer::sum);
		}
	}

	/** Read once, on the first write: waiting for it is the backend's thread's business, never the server's. */
	private ProtectionMask protection() {
		if (protection == null) {
			protection = world.protection();
		}
		return protection;
	}

	/** The same, for the lava rule. */
	private FireRule fire() {
		if (fire == null) {
			fire = new FireRule(world.fire(), allowed);
		}
		return fire;
	}

	private static Axis axis(String name) {
		return enumOf(Axis.class, name, "axis");
	}

	private static <E extends Enum<E>> E enumOf(Class<E> type, String value, String argument) {
		try {
			return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(argument + " can't be \"" + value + "\"");
		}
	}

	private static String blocks(long count) {
		return String.format(Locale.ROOT, "%,d", count) + (count == 1 ? " block" : " blocks");
	}

	private static String describe(Pos p) {
		return "(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
	}

	private static String describe(Box b) {
		return "x " + b.min().x() + ".." + b.max().x() + ", y " + b.min().y() + ".." + b.max().y() + ", z " + b.min().z() + ".." + b.max().z();
	}
}
