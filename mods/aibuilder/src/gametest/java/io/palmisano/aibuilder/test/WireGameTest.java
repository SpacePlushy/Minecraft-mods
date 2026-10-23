package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.GadgetRig.GADGETS;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.gadgets.GadgetPlacement;
import io.palmisano.aibuilder.core.gadgets.Gadgets;
import io.palmisano.aibuilder.core.gadgets.Port;
import io.palmisano.aibuilder.test.ArenaBuilds.ArenaBuild;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The wire tool in the world (spec v2 §5.4), through a scripted build: lever rows and lamp rows placed with
 * place_gadget, joined with wire, then the levers pulled and the lamps read. Gadgets stand on the floor of a gadgets
 * structure, so wires need stone under them.
 */
public class WireGameTest {
	/** Ticks from a lever's pull to a lamp's light through the longest wire here: buffers, repeaters, a lamp's turning off. */
	private static final int SETTLE = 20;

	private record Placed(JsonObject call, GadgetPlacement.Placed where) {
		List<Pos> ports() {
			return where.ports().stream().map(Port::at).toList();
		}

		Pos mark(String name) {
			return where.marks().get(name);
		}
	}

	private static Placed gadget(String kind, String options, Pos at, String facing) {
		JsonObject args = new JsonObject();
		args.addProperty("kind", kind);
		args.add("at", positions(List.of(at)).get(0));
		args.addProperty("facing", facing);
		args.add("options", JsonParser.parseString(options));
		return new Placed(call("place_gadget", args), GadgetPlacement.place(Gadgets.make(kind, JsonParser.parseString(options).getAsJsonObject()), at,
				facing));
	}

	private static JsonObject wire(List<Pos> from, List<Pos> to, List<Pos> via) {
		JsonObject args = new JsonObject();
		args.add("from", positions(from));
		args.add("to", positions(to));
		if (!via.isEmpty()) {
			args.add("via", positions(via));
		}
		return call("wire", args);
	}

	private static JsonObject call(String tool, JsonObject args) {
		JsonObject call = new JsonObject();
		call.addProperty("tool", tool);
		call.add("args", args);
		return call;
	}

	private static JsonArray positions(List<Pos> spots) {
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

	/** submit_brief, the gadgets, the wires, finish; in the small gadgets structure. */
	private static ArenaBuild build(GameTestHelper helper, List<Placed> gadgets, List<JsonObject> wires) {
		JsonArray calls = new JsonArray();
		JsonObject brief = new JsonObject();
		brief.addProperty("title", "wires");
		brief.addProperty("style", "test");
		calls.add(call("submit_brief", brief));
		gadgets.forEach(g -> calls.add(g.call()));
		wires.forEach(calls::add);
		JsonObject finish = new JsonObject();
		finish.addProperty("summary", "wired");
		calls.add(call("finish", finish));
		JsonObject script = new JsonObject();
		script.add("calls", calls);
		return ArenaBuilds.start(helper, GadgetRig.frame(helper, GadgetRig.Space.SMALL), GadgetRig.Space.SMALL.allowed(), script.toString());
	}

	private static void pull(ArenaBuild build, Pos lever) {
		BlockPos pos = build.world(lever);
		BlockState state = build.level().getBlockState(pos);
		((LeverBlock) Blocks.LEVER).pull(state, build.level(), pos, null);
	}

	private static boolean lit(ArenaBuild build, Pos lamp) {
		BlockState state = build.level().getBlockState(build.world(lamp));
		return state.is(Blocks.REDSTONE_LAMP) && state.getValue(RedstoneLampBlock.LIT);
	}

	/** The lamp is off, lights once the lever's pulled, and goes off once it's pulled back. */
	private static GameTestSequence carries(GameTestHelper helper, GameTestSequence sequence, ArenaBuild build, Pos lever, Pos lamp, String what) {
		return carries(helper, sequence, build, lever, lamp, what, SETTLE);
	}

	/** The same, waiting {@code settle} ticks after each pull (a gadget on the way takes its own). */
	private static GameTestSequence carries(GameTestHelper helper, GameTestSequence sequence, ArenaBuild build, Pos lever, Pos lamp, String what,
			int settle) {
		return sequence.thenExecute(() -> helper.assertFalse(lit(build, lamp), what + ": lit before the lever's pulled"))
				.thenExecute(() -> pull(build, lever)).thenIdle(settle)
				.thenExecute(() -> helper.assertTrue(lit(build, lamp), what + ": not lit with the lever on"))
				.thenExecute(() -> pull(build, lever)).thenIdle(settle)
				.thenExecute(() -> helper.assertFalse(lit(build, lamp), what + ": still lit with the lever off"));
	}

	private static GameTestSequence wired(GameTestHelper helper, ArenaBuild build) {
		return helper.startSequence().thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> build.replies("wire").forEach(r -> helper.assertTrue(r.startsWith("Wired"), r)));
	}

	/** One wire with no via points: out along z, across, and on along z into the lamp row: two turns. */
	@GameTest(structure = GADGETS, maxTicks = 1200)
	public void aWireCarriesASignalRoundACorner(GameTestHelper helper) {
		Placed lever = gadget("lever_row", "{\"bits\": 1}", new Pos(0, 1, 2), "forward");
		Placed lamp = gadget("lamp_row", "{\"bits\": 1}", new Pos(6, 1, 12), "forward");
		ArenaBuild build = build(helper, List.of(lever, lamp), List.of(wire(lever.ports(), lamp.ports(), List.of())));
		carries(helper, wired(helper, build), build, lever.mark("lever in0"), lamp.mark("lamp 0"), "round a corner").thenSucceed();
	}

	/** Through via points 3 up and back down: it climbs a level a block, runs level, and drops again. */
	@GameTest(structure = GADGETS, maxTicks = 1200)
	public void aWireClimbsAndDrops(GameTestHelper helper) {
		Placed lever = gadget("lever_row", "{\"bits\": 1}", new Pos(-8, 1, 2), "forward");
		Placed lamp = gadget("lamp_row", "{\"bits\": 1}", new Pos(-8, 1, 22), "forward");
		ArenaBuild build = build(helper, List.of(lever, lamp), List.of(wire(lever.ports(), lamp.ports(), List.of(new Pos(-8, 5, 10), new Pos(-8, 5, 14)))));
		GameTestSequence sequence = wired(helper, build).thenExecute(() -> {
			helper.assertTrue(build.level().getBlockState(build.world(new Pos(-8, 5, 12))).is(Blocks.REDSTONE_WIRE), "no dust 3 up");
			helper.assertTrue(build.level().getBlockState(build.world(new Pos(-8, 4, 12))).is(Blocks.STONE), "no stone under the dust 3 up");
		});
		carries(helper, sequence, build, lever.mark("lever in0"), lamp.mark("lamp 0"), "up and down").thenSucceed();
	}

	/** 60 blocks long: repeaters carry it on every 15 blocks, so the lamp at the far end still lights. */
	@GameTest(structure = GADGETS, maxTicks = 1200)
	public void aLongWireStaysStrong(GameTestHelper helper) {
		Placed lever = gadget("lever_row", "{\"bits\": 1}", new Pos(-20, 1, 2), "forward");
		Placed lamp = gadget("lamp_row", "{\"bits\": 1}", new Pos(-10, 1, 56), "forward");
		ArenaBuild build = build(helper, List.of(lever, lamp), List.of(wire(lever.ports(), lamp.ports(), List.of())));
		GameTestSequence sequence = wired(helper, build)
				.thenExecute(() -> helper.assertTrue(build.reply("wire").contains(": 57 dust, 4 repeaters (8 ticks)"), build.reply("wire")));
		carries(helper, sequence, build, lever.mark("lever in0"), lamp.mark("lamp 0"), "60 blocks").thenSucceed();
	}

	/** Three wires shifting right at once, on turning rows that keep them apart: each lever lights its own lamp alone. */
	@GameTest(structure = GADGETS, maxTicks = 1800)
	public void aRowOfWiresShifts(GameTestHelper helper) {
		Placed levers = gadget("lever_row", "{\"bits\": 3}", new Pos(-28, 1, 2), "forward");
		Placed lamps = gadget("lamp_row", "{\"bits\": 3}", new Pos(-22, 1, 20), "forward");
		ArenaBuild build = build(helper, List.of(levers, lamps), List.of(wire(levers.ports(), lamps.ports(), List.of())));
		GameTestSequence sequence = wired(helper, build);
		for (int bit = 0; bit < 3; bit++) {
			Pos lever = levers.mark("lever in" + bit);
			int on = bit;
			sequence = sequence.thenExecute(() -> pull(build, lever)).thenIdle(SETTLE).thenExecute(() -> {
				for (int lamp = 0; lamp < 3; lamp++) {
					helper.assertValueEqual(lit(build, lamps.mark("lamp " + lamp)), lamp == on, "lever " + on + " on: lamp " + lamp + " lit");
				}
			}).thenExecute(() -> pull(build, lever)).thenIdle(SETTLE);
		}
		sequence.thenSucceed();
	}

	/** One wire goes over another, 2 above it on via points: each lever still lights only its own lamp. */
	@GameTest(structure = GADGETS, maxTicks = 1800)
	public void aWireGoesOverAnother(GameTestHelper helper) {
		Placed leverA = gadget("lever_row", "{\"bits\": 1}", new Pos(10, 1, 2), "forward");
		Placed lampA = gadget("lamp_row", "{\"bits\": 1}", new Pos(10, 1, 30), "forward");
		Placed leverB = gadget("lever_row", "{\"bits\": 1}", new Pos(18, 1, 17), "left");
		Placed lampB = gadget("lamp_row", "{\"bits\": 1}", new Pos(4, 1, 17), "left");
		List<JsonObject> wires = List.of(wire(leverA.ports(), lampA.ports(), List.of()),
				wire(leverB.ports(), lampB.ports(), List.of(new Pos(13, 4, 17), new Pos(7, 4, 17))));
		ArenaBuild build = build(helper, List.of(leverA, lampA, leverB, lampB), wires);
		wired(helper, build)
				.thenExecute(() -> helper.assertTrue(build.level().getBlockState(build.world(new Pos(10, 3, 17))).is(Blocks.STONE), "no stone over wire A"))
				.thenExecute(() -> pull(build, leverA.mark("lever in0"))).thenIdle(SETTLE)
				.thenExecute(() -> {
					helper.assertTrue(lit(build, lampA.mark("lamp 0")), "lever A on: lamp A dark");
					helper.assertFalse(lit(build, lampB.mark("lamp 0")), "lever A on: lamp B lit");
				})
				.thenExecute(() -> pull(build, leverA.mark("lever in0"))).thenExecute(() -> pull(build, leverB.mark("lever in0"))).thenIdle(SETTLE)
				.thenExecute(() -> {
					helper.assertFalse(lit(build, lampA.mark("lamp 0")), "lever B on: lamp A lit");
					helper.assertTrue(lit(build, lampB.mark("lamp 0")), "lever B on: lamp B dark");
				})
				.thenSucceed();
	}

	/** A wire that would run past another gadget's buffer is refused whole, saying where, and none of it goes in. */
	@GameTest(structure = GADGETS, maxTicks = 1200)
	public void aWireBesideAGadgetIsRefused(GameTestHelper helper) {
		Placed lever = gadget("lever_row", "{\"bits\": 1}", new Pos(-10, 1, 40), "forward");
		Placed lamp = gadget("lamp_row", "{\"bits\": 1}", new Pos(-10, 1, 50), "forward");
		Placed beside = gadget("lamp_row", "{\"bits\": 1}", new Pos(-9, 1, 44), "forward");
		ArenaBuild build = build(helper, List.of(lever, lamp, beside), List.of(wire(lever.ports(), lamp.ports(), List.of())));
		List<Pos> way = new ArrayList<>();
		for (int z = 43; z <= 49; z++) {
			way.add(new Pos(-10, 2, z));
		}
		helper.startSequence().thenWaitUntil(() -> helper.assertTrue(ArenaBuilds.done(build), "still building"))
				.thenExecute(() -> {
					String reply = build.reply("wire");
					helper.assertTrue(reply.startsWith("touches_redstone: (-9, 2, 44) is redstone, beside the wire at (-10, 2, 44)")
							&& reply.contains("nothing was placed"), reply);
					for (Pos p : way) {
						helper.assertTrue(build.level().getBlockState(build.world(p)).isAir(), "something of the wire went in at " + p);
					}
				})
				.thenSucceed();
	}

	/** A wire of 15 dust arrives at strength 1: an adder's input still takes it, through its buffer (M9 review). */
	@GameTest(structure = GADGETS, maxTicks = 1200)
	public void aWeakWireStillDrivesAnAdder(GameTestHelper helper) {
		Placed lever = gadget("lever_row", "{\"bits\": 1}", new Pos(-6, 1, 0), "forward");
		Placed adder = gadget("adder", "{\"bits\": 1}", new Pos(-10, 1, 18), "forward");
		Placed lamp = gadget("lamp_row", "{\"bits\": 1}", new Pos(-8, 1, 45), "forward");
		Pos a0 = adder.where().ports().stream().filter(p -> p.name().equals("a0")).map(Port::at).findFirst().orElseThrow();
		Pos s0 = adder.where().ports().stream().filter(p -> p.name().equals("s0")).map(Port::at).findFirst().orElseThrow();
		List<JsonObject> wires = List.of(wire(lever.ports(), List.of(a0), List.of()), wire(List.of(s0), lamp.ports(), List.of()));
		ArenaBuild build = build(helper, List.of(lever, adder, lamp), wires);
		// The adder starts up as the build ends (its torches all lit at first): it settles before the lamp is looked at.
		int settle = SETTLE + Gadgets.make("adder", JsonParser.parseString("{\"bits\": 1}").getAsJsonObject()).settleTicks();
		GameTestSequence sequence = wired(helper, build).thenIdle(settle)
				.thenExecute(() -> helper.assertTrue(build.reply("wire").isEmpty() || build.replies("wire").getFirst().contains(": 15 dust,"),
						"the first wire isn't 15 dust: " + build.replies("wire")));
		carries(helper, sequence, build, lever.mark("lever in0"), lamp.mark("lamp 0"), "through the adder", settle).thenSucceed();
	}
}
