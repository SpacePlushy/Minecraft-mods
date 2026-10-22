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
import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.blocks.RejectReason;
import io.palmisano.aibuilder.core.blocks.Validation;
import io.palmisano.aibuilder.core.blocks.Validator;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.Placement;
import io.palmisano.aibuilder.core.shapes.ArchStyle;
import io.palmisano.aibuilder.core.shapes.Axis;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.shapes.FillMode;
import io.palmisano.aibuilder.core.shapes.LocalBlock;
import io.palmisano.aibuilder.core.shapes.RoofStyle;
import io.palmisano.aibuilder.core.shapes.Roofs;
import io.palmisano.aibuilder.core.shapes.Shapes;
import io.palmisano.aibuilder.core.shapes.Staircases;

/**
 * Runs the write tools: expands each shape, validates its blocks, drops positions outside the allowed box, maps
 * the rest to the world through the frame and queues them as one batch. A bad entry never fails the whole call.
 */
public final class ToolExecutor {
	private static final Set<String> TOOLS = Set.of("submit_brief", "set_blocks", "fill", "line", "wall", "cylinder", "sphere",
			"cone", "arch", "roof", "staircase", "clear", "checkpoint", "finish");
	private static final int REJECTION_LINES = 10;
	private static final BlockSpec AIR = BlockSpec.of("minecraft:air");

	private final Validator validator;
	private final LocalFrame frame;
	private final Box allowed;
	private final long maxChangedBlocks;
	private final BuildSink sink;
	private final Budget budget;
	private boolean briefed;
	private volatile boolean finished;
	private long queuedTotal;

	public ToolExecutor(Validator validator, LocalFrame frame, Box allowed, long maxChangedBlocks, BuildSink sink, Budget budget) {
		this.validator = validator;
		this.frame = frame;
		this.allowed = allowed;
		this.maxChangedBlocks = maxChangedBlocks;
		this.sink = sink;
		this.budget = budget;
	}

	public static Set<String> handledTools() {
		return TOOLS;
	}

	public boolean finished() {
		return finished;
	}

	public synchronized ToolResult call(String name, JsonObject args) {
		budget.count();
		if (finished) {
			return ToolResult.error("This build is finished.");
		}
		if (budget.exhausted() && !name.equals("finish")) {
			return ToolResult.error("Budget reached: call finish now.");
		}
		ToolResult result;
		try {
			Args a = new Args(args);
			result = switch (name) {
				case "submit_brief" -> brief(a);
				case "checkpoint" -> checkpoint(a);
				case "finish" -> finish(a);
				default -> {
					if (!TOOLS.contains(name)) {
						yield ToolResult.error("There is no tool called \"" + name + "\".");
					}
					if (!briefed) {
						yield ToolResult.error("Call submit_brief first: title, style, size, palette and phases, before placing any block.");
					}
					yield write(name, a);
				}
			};
		} catch (IllegalArgumentException e) {
			result = ToolResult.error(RejectReason.BAD_ARGUMENT.wireName() + ": " + e.getMessage());
		}
		String footer = budget.footer();
		return footer.isEmpty() ? result : new ToolResult(result.text() + "\n" + footer, result.isError());
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
		return ToolResult.ok("Checkpoint " + phase + ": " + blocks(placed) + " placed so far.");
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
		return queue(blocks, false);
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
		return queue(blocks, true);
	}

	/** Validates, bounds-checks and queues; {@code perEntry} reports each rejected entry by its index. */
	private ToolResult queue(List<LocalBlock> blocks, boolean perEntry) {
		Map<BlockSpec, Validation> checked = new HashMap<>();
		List<Placement> batch = new ArrayList<>();
		Map<String, Integer> grouped = new LinkedHashMap<>();
		List<String> lines = new ArrayList<>();
		int rejected = 0;
		for (int i = 0; i < blocks.size(); i++) {
			LocalBlock block = blocks.get(i);
			Validation v = checked.computeIfAbsent(block.block(), validator::validate);
			String problem = null;
			if (v instanceof Validation.Invalid invalid) {
				problem = invalid.rejection().reason().wireName() + ": " + invalid.rejection().detail();
			} else if (!allowed.contains(block.pos())) {
				problem = RejectReason.OUT_OF_BOUNDS.wireName() + ": " + (perEntry ? describe(block.pos()) + " is " : "")
						+ "outside the allowed box " + describe(allowed);
			} else if (queuedTotal >= maxChangedBlocks) {
				problem = RejectReason.TOO_MANY.wireName() + ": this build has reached its limit of "
						+ String.format(Locale.ROOT, "%,d", maxChangedBlocks) + " changed blocks";
			}
			if (problem != null) {
				rejected++;
				if (perEntry) {
					lines.add("#" + i + " " + problem);
				} else {
					grouped.merge(problem, 1, Integer::sum);
				}
				continue;
			}
			queuedTotal++;
			batch.add(new Placement(frame.toWorld(block.pos()), ((Validation.Valid) v).block(), block.onlyReplacing()));
		}
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
		return new ToolResult(text.toString(), batch.isEmpty() && rejected > 0);
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
