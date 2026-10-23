package io.palmisano.aibuilder.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
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
 * A gadget in every facing (spec v2 §5.3), one to a quarter of the 64 × 64 gadgets structure, built as a build places
 * blocks (the queue, the shape and power-up passes), with a lamp at each output port. Inputs are driven by redstone
 * blocks at their ports; outputs are read off the lamps.
 */
final class GadgetRig {
	static final String GADGETS = "aibuilder-test:gadgets";
	/** The allowed box, local, for the gadgets structure's frame. */
	static final Box ALLOWED = Box.of(-31, -1, 0, 31, 14, 62);
	static final List<String> FACINGS = List.of("forward", "right", "back", "left");
	/** Each facing's quarter: its lowest corner, local; a quarter is 30 × 30 with a block to spare round its ports. */
	private static final List<Pos> QUARTERS = List.of(new Pos(-30, 1, 2), new Pos(1, 1, 2), new Pos(-30, 1, 32), new Pos(1, 1, 32));

	record Rig(ArenaBuild build, List<GadgetPlacement.Placed> placed) {
	}

	private GadgetRig() {
	}

	/** The gadgets structure's frame: facing south, the origin a block in from its front, halfway across. */
	static LocalFrame frame(GameTestHelper helper) {
		return new LocalFrame(Positions.toPos(helper.absolutePos(new BlockPos(32, 0, 1))), Facing.SOUTH);
	}

	/** The gadget once in each facing, built. */
	static Rig build(GameTestHelper helper, Gadget gadget) {
		List<GadgetPlacement.Placed> placed = new ArrayList<>();
		List<LocalBlock> blocks = new ArrayList<>();
		for (int i = 0; i < FACINGS.size(); i++) {
			GadgetPlacement.Placed probe = GadgetPlacement.place(gadget, new Pos(0, 0, 0), FACINGS.get(i));
			Pos corner = QUARTERS.get(i);
			Pos at = new Pos(corner.x() - probe.box().min().x() + 1, corner.y() - probe.box().min().y(), corner.z() - probe.box().min().z() + 1);
			GadgetPlacement.Placed one = GadgetPlacement.place(gadget, at, FACINGS.get(i));
			placed.add(one);
			blocks.addAll(one.blocks());
			for (Port port : one.ports()) {
				if (!port.input()) {
					blocks.add(new LocalBlock(port.at(), BlockSpec.of("redstone_lamp")));
				}
			}
		}
		return new Rig(ArenaBuilds.start(helper, frame(helper), ALLOWED, script(blocks)), placed);
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

	/** Every combination of the inputs, bit i the gadget's input i: driven, waited the stated settle ticks, read against {@code lit}. */
	static void truthTable(GameTestHelper helper, Gadget gadget, IntFunction<Set<String>> lit) {
		helper.assertTrue(gadget.ports().stream().anyMatch(Port::input) && gadget.ports().stream().anyMatch(p -> !p.input()),
				gadget.kind() + " has no inputs or no outputs");
		Rig rig = build(helper, gadget);
		int inputs = (int) gadget.ports().stream().filter(Port::input).count();
		GameTestSequence sequence = helper.startSequence().thenWaitUntil(() -> ArenaBuilds.assertDone(helper, rig.build()));
		for (int v = 0; v < 1 << inputs; v++) {
			int value = v;
			sequence = sequence.thenExecute(() -> drive(rig, value)).thenIdle(gadget.settleTicks()).thenExecute(() -> check(helper, rig, value, lit));
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

	/** The outputs lit in each facing, against {@code lit}. */
	static void check(GameTestHelper helper, Rig rig, int value, IntFunction<Set<String>> lit) {
		Set<String> expected = lit.apply(value);
		for (int i = 0; i < rig.placed().size(); i++) {
			Set<String> on = lit(rig, rig.placed().get(i));
			helper.assertValueEqual(on, expected, FACINGS.get(i) + ", inputs " + value + ": lit outputs");
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
