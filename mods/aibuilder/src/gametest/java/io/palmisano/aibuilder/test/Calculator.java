package io.palmisano.aibuilder.test;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.gadgets.Display;
import io.palmisano.aibuilder.core.gadgets.Gadget;
import io.palmisano.aibuilder.core.gadgets.GadgetPlacement;
import io.palmisano.aibuilder.core.gadgets.Gadgets;
import io.palmisano.aibuilder.core.gadgets.Port;
import io.palmisano.aibuilder.test.ArenaBuilds.ArenaBuild;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The calculator the tests build from gadgets and wires (the M9 plan), in a build's local frame on a big gadgets
 * structure: lever row b in front, lever row a behind it, a 4-bit adder, a 5-bit number. Row a's wires run low (level 2)
 * and climb into the adder at the end; row b's run high (level 8) over them; the sums run into the number. The fake
 * Claude's calculator scenario places the same gadgets at the same spots.
 */
final class Calculator {
	record Part(String kind, String options, Pos at) {
		Gadget gadget() {
			return Gadgets.make(kind, JsonParser.parseString(options).getAsJsonObject());
		}

		GadgetPlacement.Placed placed() {
			return GadgetPlacement.place(gadget(), at, "forward");
		}

		Pos port(String name) {
			return placed().ports().stream().filter(p -> p.name().equals(name)).map(Port::at).findFirst().orElseThrow();
		}

		JsonObject placeCall() {
			JsonObject args = new JsonObject();
			args.addProperty("kind", kind);
			args.add("at", positions(List.of(at)).get(0));
			args.addProperty("facing", "forward");
			args.add("options", JsonParser.parseString(options));
			return call("place_gadget", args);
		}
	}

	static final Part B = new Part("lever_row", "{\"bits\": 4, \"label\": \"b\"}", new Pos(-9, 1, 0));
	static final Part A = new Part("lever_row", "{\"bits\": 4, \"label\": \"a\"}", new Pos(-23, 1, 6));
	static final Part ADDER = new Part("adder", "{\"bits\": 4}", new Pos(-27, 1, 20));
	static final Part NUMBER = new Part("number", "{\"bits\": 5}", new Pos(-25, 1, 52));

	private Calculator() {
	}

	static List<JsonObject> gadgetCalls() {
		return List.of(B.placeCall(), A.placeCall(), ADDER.placeCall(), NUMBER.placeCall());
	}

	static List<JsonObject> wireCalls() {
		return List.of(wire(ports(A, "a", 4), ports(ADDER, "a", 4), 2), wire(ports(B, "b", 4), ports(ADDER, "b", 4), 8),
				wire(ports(ADDER, "s", 5), ports(NUMBER, "n", 5), null));
	}

	/** A test of 9 + 7 and of 15 + 15 with show, as the fake does. */
	static JsonObject testCall() {
		JsonArray steps = new JsonArray();
		for (int[] sum : new int[][] {{9, 7}, {15, 15}}) {
			for (int bit = 0; bit < 4; bit++) {
				steps.add(set(A.placed().marks().get("lever a" + bit), (sum[0] >> bit & 1) == 1));
				steps.add(set(B.placed().marks().get("lever b" + bit), (sum[1] >> bit & 1) == 1));
			}
			JsonObject wait = new JsonObject();
			wait.addProperty("wait", settle());
			steps.add(wait);
			JsonObject show = new JsonObject();
			show.addProperty("show", true);
			steps.add(show);
		}
		JsonObject args = new JsonObject();
		args.add("steps", steps);
		return call("test_redstone", args);
	}

	private static JsonObject set(Pos lever, boolean on) {
		JsonObject step = new JsonObject();
		step.add("set", positions(List.of(lever)).get(0));
		step.addProperty("on", on);
		return step;
	}

	/** Ticks from a lever to the number: the adder's carry, the number's arrays, the wires' repeaters. */
	static int settle() {
		return ADDER.gadget().settleTicks() + NUMBER.gadget().settleTicks() + 30;
	}

	/** submit_brief, {@code calls}, finish. */
	static String script(List<JsonObject> calls) {
		JsonArray all = new JsonArray();
		JsonObject brief = new JsonObject();
		brief.addProperty("title", "calculator");
		brief.addProperty("style", "test");
		all.add(call("submit_brief", brief));
		calls.forEach(all::add);
		JsonObject finish = new JsonObject();
		finish.addProperty("summary", "a calculator");
		all.add(call("finish", finish));
		JsonObject script = new JsonObject();
		script.add("calls", all);
		return script.toString();
	}

	/** Sets the levers to a and b (each 0 to 15), pulling only those that differ. */
	static void set(ArenaBuild build, int a, int b) {
		for (int bit = 0; bit < 4; bit++) {
			pull(build, A.placed().marks().get("lever a" + bit), (a >> bit & 1) == 1);
			pull(build, B.placed().marks().get("lever b" + bit), (b >> bit & 1) == 1);
		}
	}

	private static void pull(ArenaBuild build, Pos lever, boolean on) {
		BlockPos pos = build.world(lever);
		BlockState state = build.level().getBlockState(pos);
		if (state.getValue(LeverBlock.POWERED) != on) {
			((LeverBlock) Blocks.LEVER).pull(state, build.level(), pos, null);
		}
	}

	/** What the number shows. */
	static String shows(ArenaBuild build) {
		return Display.number(NUMBER.placed().marks(), p -> {
			BlockState lamp = build.level().getBlockState(build.world(p));
			return lamp.is(Blocks.REDSTONE_LAMP) && lamp.getValue(RedstoneLampBlock.LIT);
		});
	}

	private static List<Pos> ports(Part part, String prefix, int count) {
		List<Pos> out = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			out.add(part.port(prefix + i));
		}
		return out;
	}

	private static JsonObject wire(List<Pos> from, List<Pos> to, Integer level) {
		JsonObject args = new JsonObject();
		args.add("from", positions(from));
		args.add("to", positions(to));
		if (level != null) {
			args.addProperty("level", level);
		}
		return call("wire", args);
	}

	static JsonObject call(String tool, JsonObject args) {
		JsonObject call = new JsonObject();
		call.addProperty("tool", tool);
		call.add("args", args);
		return call;
	}

	static JsonArray positions(List<Pos> spots) {
		JsonArray out = new JsonArray();
		for (Pos p : spots) {
			JsonArray a = new JsonArray();
			a.add(p.x());
			a.add(p.y());
			a.add(p.z());
			out.add(a);
		}
		return out;
	}
}
