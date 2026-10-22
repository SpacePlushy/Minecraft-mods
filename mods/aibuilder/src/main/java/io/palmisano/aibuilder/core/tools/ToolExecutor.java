package io.palmisano.aibuilder.core.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.blocks.BlockEntityData;
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
 * Runs the write tools: expands each shape, validates its blocks, drops positions outside the allowed box, maps
 * the rest to the world through the frame and queues them as one batch. A bad entry never fails the whole call.
 */
public final class ToolExecutor {
	private static final Set<String> TOOLS = Set.of("get_context", "survey_area", "inspect_region", "search_blocks", "submit_brief",
			"set_blocks", "fill", "line", "wall", "cylinder", "sphere", "cone", "arch", "roof", "staircase", "clear", "terraform",
			"place_block_entity", "checkpoint", "check_build", "finish");
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
	private final WorldReader world;
	private ProtectionMask protection;
	private FireRule fire;
	private boolean briefed;
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
		return ToolResult.ok(String.join("\n",
				"Frame: you build in a local frame. +x is the player's right, +z is forward (away from the player), +y is up; y=0 is the ground at"
						+ " the origin, " + Tunables.SITE_GAP + " blocks in front of the player. Block states take forward, back, left, right, up and"
						+ " down for directions, never compass names.",
				"Size: " + tier.label() + " (" + tier.size() + " blocks): the allowed box is " + describe(allowed) + "; at most "
						+ String.format(Locale.ROOT, "%,d", maxChangedBlocks) + " changed blocks.",
				"World: " + facts.dimension() + ", biome " + facts.biome() + ", " + facts.time() + ", " + facts.weather()
						+ "; the ground block at the origin is " + facts.groundBlock() + ".",
				"The player stands at x " + facts.player().x() + ", y " + facts.player().y() + ", z " + facts.player().z() + ".",
				"Budget: " + left + " of " + budget.max() + " tool calls left."));
	}

	/** Centred on the allowed box's footprint; the default radius covers it with 8 blocks to spare. */
	private ToolResult survey(Args a) {
		int radius = Math.max(1, Math.min(a.optInt("radius", tier.size() / 2 + 8), Tunables.SURVEY_RADIUS_MAX));
		int centreX = Math.floorDiv(allowed.min().x() + allowed.max().x() + 1, 2);
		int centreZ = Math.floorDiv(allowed.min().z() + allowed.max().z() + 1, 2);
		TerrainSnapshot snapshot = world.terrain(centreX - radius, centreZ - radius, 2 * radius + 1, 2 * radius + 1);
		return ToolResult.ok(SurveyEncoder.encode(snapshot, allowed, Tunables.SURVEY_MAX_CHARS));
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
		return ToolResult.ok(RegionEncoder.encode(box, world.states(box)));
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
			return queue(blocks, false, false);
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
		Brief brief = new Brief(a.string("title"), a.optString("style", ""), size.optInt("w", 0), size.optInt("h", 0), size.optInt("d", 0),
				palette, phases);
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
		sink.awaitPlaced();
		long waiting = sink.pending();
		if (waiting > 0) {
			// Half a build would show half its problems.
			return ToolResult.ok(String.format(Locale.ROOT, "%,d", waiting)
					+ " blocks are still going in; call checkpoint to wait for them, then check_build again.");
		}
		return ToolResult.ok(BuildProblems.encode(world.check(), Tunables.CHECK_BUILD_LISTED));
	}

	private ToolResult finish(Args a) {
		finished = true;
		sink.finish(a.optString("summary", ""));
		return ToolResult.ok("Build finished.");
	}

	private ToolResult write(String name, Args a) {
		if (name.equals("set_blocks")) {
			return setBlocks(a);
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
		return queue(blocks, false, name.equals("clear") && a.optBool("include_protected", false));
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
			case "staircase" -> a.string("style").equals("spiral")
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
		String mode = a.optString("mode", "solid");
		if (mode.equals("replace")) {
			return Shapes.replace(a.box("box"), a.block("block"), a.string("filter"));
		}
		return Shapes.fill(a.box("box"), a.block("block"), enumOf(FillMode.class, mode, "mode"));
	}

	private static List<LocalBlock> staircase(Args a) {
		return switch (a.string("style")) {
			case "straight" -> Staircases.straight(a.pos("from"), a.string("direction"), a.integer("steps"), a.optInt("width", 1),
					a.block("block"));
			case "spiral" -> Staircases.spiral(a.pos("center"), a.integer("radius"), a.integer("from_y"), a.integer("to_y"),
					a.block("block"), a.optBool("clockwise", true));
			default -> throw new IllegalArgumentException("style must be straight or spiral");
		};
	}

	private ToolResult setBlocks(Args a) {
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
		return queue(blocks, true, false);
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
		if (validator.validate(spec) instanceof Validation.Valid valid && valid.block().info().entity() != data.kind()) {
			return ToolResult.error(RejectReason.NOT_A_BLOCK_ENTITY.wireName() + ": " + valid.block().info().id() + " can't hold "
					+ data.kind().name().toLowerCase(Locale.ROOT) + " data; use " + switch (data.kind()) {
						case SIGN -> "a sign (oak_sign, oak_wall_sign, oak_hanging_sign…)";
						case CONTAINER -> "storage that keeps its items (chest, trapped_chest, barrel, a shulker box, dispenser, dropper, decorated_pot)";
						case LECTERN -> "a lectern";
						default -> "a banner (white_banner, red_wall_banner…)";
					});
		}
		return queue(List.of(new LocalBlock(pos, spec, null, data)), true, false);
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
	private ToolResult queue(List<LocalBlock> blocks, boolean perEntry, boolean includeProtected) {
		if (sink.capReached()) {
			return ToolResult.error(RejectReason.TOO_MANY.wireName() + ": this build has reached its limit of "
					+ String.format(Locale.ROOT, "%,d", maxChangedBlocks) + " changed blocks; call finish");
		}
		ProtectionMask mask = protection();
		Map<BlockSpec, Validation> checked = new HashMap<>();
		int[] fit = new int[blocks.size()];
		int fitCount = 0;
		Map<String, Integer> grouped = new LinkedHashMap<>();
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < blocks.size(); i++) {
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
		FireRule fire = fire();
		List<Placement> batch = new ArrayList<>(fitCount);
		List<Pos> claimed = new ArrayList<>(fitCount);
		for (int n = 0; n < fitCount; n++) {
			LocalBlock block = blocks.get(fit[n]);
			ResolvedBlock resolved = ((Validation.Valid) checked.get(block.block())).block();
			FireRule.Kind kind = FireRule.Kind.of(resolved.info());
			if (!fire.admit(block.pos(), kind, block.onlyReplacing() != null)) {
				reject(lines, grouped, perEntry, fit[n], kind == FireRule.Kind.LAVA ? LAVA_BY_FLAMMABLE : FLAMMABLE_BY_LAVA);
				continue;
			}
			claimed.add(block.pos());
			batch.add(new Placement(frame.toWorld(block.pos()), resolved, block.onlyReplacing(), block.data()));
		}
		int rejected = blocks.size() - batch.size();
		// Those spots are the build's own from now on, even where it may clear a protected block.
		claimed.forEach(mask::release);
		grouped.forEach((problem, count) -> lines.add(blocks(count) + " — " + problem));
		if (!batch.isEmpty()) {
			sink.queue(batch);
		}
		StringBuilder text = new StringBuilder("Queued ").append(blocks(batch.size())).append('.');
		if (rejected > 0) {
			text.append(" Rejected ").append(String.format(Locale.ROOT, "%,d", rejected)).append(':');
			lines.stream().limit(REJECTION_LINES).forEach(line -> text.append('\n').append(line));
			if (lines.size() > REJECTION_LINES) {
				text.append("\n… and ").append(lines.size() - REJECTION_LINES).append(" more");
			}
		}
		return new ToolResult(text.toString(), batch.isEmpty() && rejected > 0, List.copyOf(lines));
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
