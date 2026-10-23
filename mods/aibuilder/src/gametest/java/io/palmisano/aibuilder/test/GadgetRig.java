package io.palmisano.aibuilder.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.gadgets.Display;
import io.palmisano.aibuilder.core.gadgets.Gadget;
import io.palmisano.aibuilder.core.gadgets.GadgetPlacement;
import io.palmisano.aibuilder.core.gadgets.Port;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.shapes.LocalBlock;
import io.palmisano.aibuilder.test.ArenaBuilds.ArenaBuild;
import io.palmisano.aibuilder.world.Positions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A gadget in every facing (spec v2 §5.3), one to a quarter of a gadgets structure, built as a build places blocks
 * (the queue, the shape and power-up passes), with a lamp at each output port; with a twin, a second copy a block to
 * its right, driven the same. Inputs are driven by redstone blocks at their ports; outputs are read off the lamps.
 */
final class GadgetRig {
	static final String GADGETS = "aibuilder-test:gadgets";
	static final String GADGETS_BIG = "aibuilder-test:gadgets_big";
	static final List<String> FACINGS = List.of("forward", "right", "back", "left");

	/** A gadgets structure: its size across and deep (square), and so its frame, allowed box and quarters. */
	enum Space {
		SMALL(64), BIG(128);

		private final int size;

		Space(int size) {
			this.size = size;
		}

		/** The allowed box, local: the whole structure but its edge. */
		Box allowed() {
			int half = size / 2;
			return Box.of(-(half - 1), -1, 0, half - 1, 14, size - 2);
		}

		/** Each facing's quarter, its lowest corner, local; a quarter is {@code size / 2 - 2} square. */
		Pos quarter(int i) {
			int half = size / 2;
			return new Pos(i % 2 == 0 ? -(half - 2) : 1, 1, i < 2 ? 2 : half);
		}
	}

	record Rig(ArenaBuild build, List<GadgetPlacement.Placed> placed) {
	}

	private GadgetRig() {
	}

	/** A gadgets structure's frame: facing south, the origin a block in from its front, halfway across. */
	static LocalFrame frame(GameTestHelper helper, Space space) {
		return new LocalFrame(Positions.toPos(helper.absolutePos(new BlockPos(space.size / 2, 0, 1))), Facing.SOUTH);
	}

	/** The build's way to a gadget's right, for each facing. */
	private static Pos right(String facing) {
		return switch (facing) {
			case "forward" -> new Pos(1, 0, 0);
			case "right" -> new Pos(0, 0, -1);
			case "back" -> new Pos(-1, 0, 0);
			default -> new Pos(0, 0, 1);
		};
	}

	/** The gadget once in each facing (and its twin a block to its right), built. */
	static Rig build(GameTestHelper helper, Space space, Gadget gadget, boolean twin) {
		List<GadgetPlacement.Placed> placed = new ArrayList<>();
		List<LocalBlock> blocks = new ArrayList<>();
		for (int i = 0; i < FACINGS.size(); i++) {
			String facing = FACINGS.get(i);
			Pos side = right(facing);
			int apart = gadget.size().x() + 1;
			List<Pos> spots = new ArrayList<>(List.of(new Pos(0, 0, 0)));
			if (twin) {
				spots.add(new Pos(side.x() * apart, 0, side.z() * apart));
			}
			// Where the copies' boxes (and their ports, a block out) start, placed from 0, to shift them into the quarter.
			int minX = Integer.MAX_VALUE;
			int minY = Integer.MAX_VALUE;
			int minZ = Integer.MAX_VALUE;
			for (Pos spot : spots) {
				Box box = GadgetPlacement.place(gadget, spot, facing).box();
				minX = Math.min(minX, box.min().x());
				minY = Math.min(minY, box.min().y());
				minZ = Math.min(minZ, box.min().z());
			}
			Pos corner = space.quarter(i);
			for (Pos spot : spots) {
				Pos at = new Pos(corner.x() - minX + 1 + spot.x(), corner.y() - minY + spot.y(), corner.z() - minZ + 1 + spot.z());
				GadgetPlacement.Placed one = GadgetPlacement.place(gadget, at, facing);
				placed.add(one);
				blocks.addAll(one.blocks());
				for (Port port : one.ports()) {
					if (!port.input()) {
						blocks.add(new LocalBlock(port.at(), BlockSpec.of("redstone_lamp")));
					}
				}
			}
		}
		return new Rig(ArenaBuilds.start(helper, frame(helper, space), space.allowed(), script(blocks)), placed);
	}

	/** submit_brief, set_blocks of {@code blocks} (in calls of at most SET_BLOCKS_MAX), finish. */
	static String script(List<LocalBlock> blocks) {
		JsonArray calls = new JsonArray();
		calls.add(call("submit_brief", Map.of("title", "gadget", "style", "test")));
		for (int from = 0; from < blocks.size(); from += Tunables.SET_BLOCKS_MAX) {
			JsonArray batch = new JsonArray();
			for (LocalBlock block : blocks.subList(from, Math.min(blocks.size(), from + Tunables.SET_BLOCKS_MAX))) {
				JsonObject entry = new JsonObject();
				JsonArray pos = new JsonArray();
				pos.add(block.pos().x());
				pos.add(block.pos().y());
				pos.add(block.pos().z());
				entry.add("pos", pos);
				entry.addProperty("block", block.block().id());
				if (!block.block().state().isEmpty()) {
					JsonObject state = new JsonObject();
					block.block().state().forEach(state::addProperty);
					entry.add("state", state);
				}
				batch.add(entry);
			}
			JsonObject args = new JsonObject();
			args.add("blocks", batch);
			JsonObject set = new JsonObject();
			set.addProperty("tool", "set_blocks");
			set.add("args", args);
			calls.add(set);
		}
		calls.add(call("finish", Map.of("summary", "built")));
		JsonObject script = new JsonObject();
		script.add("calls", calls);
		return script.toString();
	}

	private static JsonObject call(String tool, Map<String, String> args) {
		JsonObject call = new JsonObject();
		call.addProperty("tool", tool);
		JsonObject a = new JsonObject();
		args.forEach(a::addProperty);
		call.add("args", a);
		return call;
	}

	/** Every combination of the inputs, in the small space, no twin. */
	static void truthTable(GameTestHelper helper, Gadget gadget, IntFunction<Set<String>> lit) {
		truthTable(helper, Space.SMALL, gadget, false, lit);
	}

	/** Every combination of the inputs, bit i the gadget's input i: driven, waited the stated settle ticks, read against {@code lit}. */
	static void truthTable(GameTestHelper helper, Space space, Gadget gadget, boolean twin, IntFunction<Set<String>> lit) {
		int inputs = (int) gadget.ports().stream().filter(Port::input).count();
		samples(helper, space, gadget, twin, IntStream.range(0, 1 << inputs).boxed().toList(), lit);
	}

	/** Each of {@code values} (bit i the gadget's input i): driven, waited the stated settle ticks, read against {@code lit}. */
	static void samples(GameTestHelper helper, Space space, Gadget gadget, boolean twin, List<Integer> values, IntFunction<Set<String>> lit) {
		helper.assertTrue(gadget.ports().stream().anyMatch(Port::input) && gadget.ports().stream().anyMatch(p -> !p.input()),
				gadget.kind() + " has no inputs or no outputs");
		Rig rig = build(helper, space, gadget, twin);
		GameTestSequence sequence = helper.startSequence().thenWaitUntil(() -> ArenaBuilds.assertDone(helper, rig.build()));
		for (int value : values) {
			sequence = sequence.thenExecute(() -> drive(rig, value)).thenIdle(gadget.settleTicks()).thenExecute(() -> check(helper, rig, value, lit));
		}
		sequence.thenSucceed();
	}

	/** Each of {@code values} driven as for {@link #samples}; each placement's digit read off its face against {@code shown}. */
	static void display(GameTestHelper helper, Space space, Gadget gadget, boolean twin, List<Integer> values, IntFunction<String> shown) {
		Rig rig = build(helper, space, gadget, twin);
		GameTestSequence sequence = helper.startSequence().thenWaitUntil(() -> ArenaBuilds.assertDone(helper, rig.build()));
		int copies = twin ? 2 : 1;
		for (int value : values) {
			sequence = sequence.thenExecute(() -> drive(rig, value)).thenIdle(gadget.settleTicks()).thenExecute(() -> {
				for (int i = 0; i < rig.placed().size(); i++) {
					String read = Display.read(rig.placed().get(i).marks(), p -> {
						BlockState lamp = rig.build().level().getBlockState(rig.build().world(p));
						return lamp.is(Blocks.REDSTONE_LAMP) && lamp.getValue(RedstoneLampBlock.LIT);
					});
					helper.assertValueEqual(read, shown.apply(value), FACINGS.get(i / copies) + (i % copies == 1 ? " (the twin)" : "") + ", code " + value);
				}
			});
		}
		sequence.thenSucceed();
	}

	/** Input i on (a redstone block at its port) when bit i of {@code value} is set, else off. */
	static void drive(Rig rig, int value) {
		for (GadgetPlacement.Placed one : rig.placed()) {
			int bit = 0;
			for (Port port : one.ports()) {
				if (port.input()) {
					BlockState state = (value >> bit++ & 1) == 1 ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState();
					rig.build().level().setBlock(rig.build().world(port.at()), state, 3);
				}
			}
		}
	}

	/** The outputs lit in each placement, against {@code lit}. */
	static void check(GameTestHelper helper, Rig rig, int value, IntFunction<Set<String>> lit) {
		Set<String> expected = lit.apply(value);
		int copies = rig.placed().size() / FACINGS.size();
		for (int i = 0; i < rig.placed().size(); i++) {
			Set<String> on = lit(rig, rig.placed().get(i));
			helper.assertValueEqual(on, expected, FACINGS.get(i / copies) + (i % copies == 1 ? " (the twin)" : "") + ", inputs " + value + ": lit outputs");
		}
	}

	/** The names of the outputs whose lamps are lit. */
	static Set<String> lit(Rig rig, GadgetPlacement.Placed one) {
		return one.ports().stream().filter(p -> !p.input()).filter(p -> {
			BlockState lamp = rig.build().level().getBlockState(rig.build().world(p.at()));
			return lamp.is(Blocks.REDSTONE_LAMP) && lamp.getValue(RedstoneLampBlock.LIT);
		}).map(Port::name).collect(Collectors.toSet());
	}
}
