package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.GadgetRig.GADGETS;
import static io.palmisano.aibuilder.test.GadgetRig.GADGETS_BIG;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonParser;
import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.gadgets.Gadget;
import io.palmisano.aibuilder.core.gadgets.GadgetPlacement;
import io.palmisano.aibuilder.core.gadgets.Gadgets;
import io.palmisano.aibuilder.core.gadgets.Port;
import io.palmisano.aibuilder.core.shapes.LocalBlock;
import io.palmisano.aibuilder.test.ArenaBuilds.ArenaBuild;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;

/** The gadgets in a real world (spec v2 §5.3): each in all four facings, beside a twin of itself at their closest. */
public class GadgetGameTest {
	private static Gadget make(String kind, String options) {
		return Gadgets.make(kind, JsonParser.parseString(options).getAsJsonObject());
	}

	/** The sum's bits lit, s0 up: {@code s<k>} for each bit k of {@code sum}. */
	private static Set<String> sum(int sum, int bits) {
		Set<String> lit = new HashSet<>();
		for (int k = 0; k <= bits; k++) {
			if ((sum >> k & 1) == 1) {
				lit.add("s" + k);
			}
		}
		return lit;
	}

	/**
	 * A lever row and a lamp row joined port to port by two blocks of dust each, in every facing: each lever lights its
	 * own lamp and no other.
	 */
	@GameTest(structure = GADGETS, maxTicks = 1200)
	public void aLeverRowLightsALampRow(GameTestHelper helper) {
		Gadget levers = make("lever_row", "{\"bits\": 2, \"label\": \"a\"}");
		Gadget lamps = make("lamp_row", "{\"bits\": 2}");
		List<GadgetPlacement.Placed> leverRows = new ArrayList<>();
		List<GadgetPlacement.Placed> lampRows = new ArrayList<>();
		List<LocalBlock> blocks = new ArrayList<>();
		List<Pos> corners = List.of(new Pos(-20, 1, 10), new Pos(10, 1, 10), new Pos(-10, 1, 50), new Pos(20, 1, 50));
		for (int i = 0; i < 4; i++) {
			String facing = GadgetRig.FACINGS.get(i);
			Pos at = corners.get(i);
			GadgetPlacement.Placed row = GadgetPlacement.place(levers, at, facing);
			// The lamp row 5 further on, so each output and its input are two dust apart.
			GadgetPlacement.Placed probe = GadgetPlacement.place(levers, new Pos(0, 0, 0), facing);
			Pos ahead = GadgetPlacement.place(levers, new Pos(0, 0, 0), facing).ports().getFirst().at();
			Pos step = new Pos(Integer.signum(ahead.x() - probe.marks().get("lever a0").x()) * 5, 0, Integer.signum(ahead.z()
					- probe.marks().get("lever a0").z()) * 5);
			GadgetPlacement.Placed lampRow = GadgetPlacement.place(lamps, new Pos(at.x() + step.x(), at.y(), at.z() + step.z()), facing);
			leverRows.add(row);
			lampRows.add(lampRow);
			blocks.addAll(row.blocks());
			blocks.addAll(lampRow.blocks());
			for (int bit = 0; bit < 2; bit++) {
				Pos out = row.ports().get(bit).at();
				Pos in = lampRow.ports().get(bit).at();
				for (Pos dust : List.of(out, in)) {
					blocks.add(new LocalBlock(dust, BlockSpec.of("redstone_wire")));
					blocks.add(new LocalBlock(new Pos(dust.x(), dust.y() - 1, dust.z()), BlockSpec.of("stone")));
				}
			}
		}
		ArenaBuild build = ArenaBuilds.start(helper, GadgetRig.frame(helper, GadgetRig.Space.SMALL), GadgetRig.Space.SMALL.allowed(),
				GadgetRig.script(blocks));
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> leverRows.forEach(row -> pull(build, row.marks().get("lever a0"))))
				.thenIdle(levers.settleTicks() + lamps.settleTicks() + 4)
				.thenExecute(() -> lit(helper, build, lampRows, "lamp 0"))
				.thenExecute(() -> leverRows.forEach(row -> {
					pull(build, row.marks().get("lever a0"));
					pull(build, row.marks().get("lever a1"));
				}))
				.thenIdle(levers.settleTicks() + lamps.settleTicks() + 4)
				.thenExecute(() -> lit(helper, build, lampRows, "lamp 1"))
				.thenSucceed();
	}

	private static void pull(ArenaBuild build, Pos lever) {
		BlockPos pos = build.world(lever);
		BlockState state = build.level().getBlockState(pos);
		((LeverBlock) Blocks.LEVER).pull(state, build.level(), pos, null);
	}

	/** In every facing, only lamp {@code on} of each lamp row is lit. */
	private static void lit(GameTestHelper helper, ArenaBuild build, List<GadgetPlacement.Placed> lampRows, String on) {
		for (int i = 0; i < lampRows.size(); i++) {
			for (var mark : lampRows.get(i).marks().entrySet()) {
				BlockState lamp = build.level().getBlockState(build.world(mark.getValue()));
				helper.assertValueEqual(lamp.getValue(RedstoneLampBlock.LIT), mark.getKey().equals(on), GadgetRig.FACINGS.get(i) + ": " + mark.getKey() + " lit");
			}
		}
	}

	/** A digit shows every BCD code, 0 to 9, in every facing, beside a twin of itself a block away. */
	@GameTest(structure = GADGETS_BIG, maxTicks = 3000)
	public void aDigitShowsEveryBcdCode(GameTestHelper helper) {
		GadgetRig.display(helper, GadgetRig.Space.BIG, make("digit", "{}"), true, List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), String::valueOf);
	}

	/** A 2-bit adder adds every pair, with a carry in, in every facing, beside a twin of itself a block away. */
	@GameTest(structure = GADGETS_BIG, maxTicks = 6000)
	public void anAdderAddsEveryPairOf2Bits(GameTestHelper helper) {
		Gadget adder = make("adder", "{\"bits\": 2}");
		helper.assertValueEqual(adder.ports().stream().filter(Port::input).count(), 5L, "inputs");
		GadgetRig.truthTable(helper, GadgetRig.Space.BIG, adder, true, v -> sum((v & 3) + (v >> 2 & 3) + (v >> 4 & 1), 2));
	}

	/** A 4-bit adder, samples of its sums, in every facing. */
	@GameTest(structure = GADGETS_BIG, maxTicks = 6000)
	public void anAdderOf4BitsAddsSamples(GameTestHelper helper) {
		Gadget adder = make("adder", "{\"bits\": 4}");
		List<Integer> samples = new ArrayList<>();
		for (int[] pair : new int[][] {{0, 0}, {1, 1}, {7, 9}, {15, 15}, {6, 3}, {12, 5}, {15, 0}, {8, 8}}) {
			samples.add(pair[0] | pair[1] << 4);
		}
		GadgetRig.samples(helper, GadgetRig.Space.BIG, adder, false, samples, v -> sum((v & 15) + (v >> 4 & 15), 4));
	}

	/** A clock of 16 ticks in every facing: its output rises every 16 ticks; with hold on, it stops. */
	@GameTest(structure = GADGETS, maxTicks = 1200)
	public void aClockTicksAtItsPeriod(GameTestHelper helper) {
		Gadget clock = make("clock", "{\"period\": 16}");
		GadgetRig.Rig rig = GadgetRig.build(helper, GadgetRig.Space.SMALL, clock, true);
		List<List<Long>> rises = new ArrayList<>();
		List<Boolean> was = new ArrayList<>();
		rig.placed().forEach(p -> {
			rises.add(new ArrayList<>());
			was.add(false);
		});
		AtomicBoolean watching = new AtomicBoolean(false);
		helper.onEachTick(() -> {
			if (!watching.get()) {
				return;
			}
			for (int i = 0; i < rig.placed().size(); i++) {
				boolean now = GadgetRig.on(rig, rig.placed().get(i), "out");
				if (now && !was.get(i)) {
					rises.get(i).add(helper.getTick());
				}
				was.set(i, now);
			}
		});
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, rig.build()))
				.thenExecute(() -> watching.set(true))
				.thenIdle(16 * 4 + 2)
				.thenExecute(() -> {
					for (int i = 0; i < rises.size(); i++) {
						List<Long> r = rises.get(i);
						helper.assertTrue(r.size() >= 3, GadgetRig.FACINGS.get(i / 2) + ": " + r.size() + " rises in 4 periods");
						for (int k = 1; k < r.size(); k++) {
							helper.assertValueEqual(r.get(k) - r.get(k - 1), 16L, GadgetRig.FACINGS.get(i / 2) + ": ticks between rises");
						}
					}
					GadgetRig.drive(rig, 1);
					rises.forEach(List::clear);
				})
				.thenIdle(clock.settleTicks())
				.thenExecute(() -> rises.forEach(List::clear))
				.thenIdle(40)
				.thenExecute(() -> {
					for (int i = 0; i < rises.size(); i++) {
						helper.assertValueEqual(rises.get(i).size(), 0, GadgetRig.FACINGS.get(i / 2) + ": rises while held");
					}
				})
				.thenSucceed();
	}

	/** A pulse maker of 4 ticks in every facing: a rising input gives one 4-tick pulse; the fall gives none. */
	@GameTest(structure = GADGETS, maxTicks = 1200)
	public void aPulseIsAsLongAsAsked(GameTestHelper helper) {
		Gadget pulse = make("pulse", "{\"length\": 4}");
		GadgetRig.Rig rig = GadgetRig.build(helper, GadgetRig.Space.SMALL, pulse, true);
		List<AtomicInteger> onTicks = new ArrayList<>();
		rig.placed().forEach(p -> onTicks.add(new AtomicInteger()));
		helper.onEachTick(() -> {
			if (!ArenaBuilds.done(rig.build())) {
				return;
			}
			for (int i = 0; i < rig.placed().size(); i++) {
				if (GadgetRig.on(rig, rig.placed().get(i), "out")) {
					onTicks.get(i).incrementAndGet();
				}
			}
		});
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, rig.build()))
				.thenIdle(10)
				.thenExecute(() -> onTicks.forEach(c -> c.set(0)))
				.thenExecute(() -> GadgetRig.drive(rig, 1))
				.thenIdle(30)
				.thenExecute(() -> {
					for (int i = 0; i < onTicks.size(); i++) {
						helper.assertValueEqual(onTicks.get(i).get(), 4, GadgetRig.FACINGS.get(i / 2) + ": ticks the pulse lasted");
						onTicks.get(i).set(0);
					}
					GadgetRig.drive(rig, 0);
				})
				.thenIdle(30)
				.thenExecute(() -> {
					for (int i = 0; i < onTicks.size(); i++) {
						helper.assertValueEqual(onTicks.get(i).get(), 0, GadgetRig.FACINGS.get(i / 2) + ": ticks on after the fall");
					}
				})
				.thenSucceed();
	}

	/** A toggle in every facing: each pulse at its input flips its output. */
	@GameTest(structure = GADGETS, maxTicks = 1200)
	public void aToggleFlipsOnEachPulse(GameTestHelper helper) {
		Gadget toggle = make("toggle", "{}");
		GadgetRig.Rig rig = GadgetRig.build(helper, GadgetRig.Space.SMALL, toggle, true);
		GameTestSequence sequence = helper.startSequence().thenWaitUntil(() -> ArenaBuilds.assertDone(helper, rig.build())).thenIdle(10);
		for (int pulse = 1; pulse <= 3; pulse++) {
			boolean expected = pulse % 2 == 1;
			int n = pulse;
			sequence = sequence.thenExecute(() -> GadgetRig.drive(rig, 1)).thenIdle(toggle.settleTicks()).thenExecute(() -> GadgetRig.drive(rig, 0))
					.thenIdle(toggle.settleTicks()).thenExecute(() -> {
						for (int i = 0; i < rig.placed().size(); i++) {
							helper.assertValueEqual(GadgetRig.on(rig, rig.placed().get(i), "out"), expected, GadgetRig.FACINGS.get(i / 2) + ", after pulse " + n);
						}
					});
		}
		sequence.thenSucceed();
	}

	/** A latch of 2 bits in every facing: its outputs follow d while hold is off, and keep what they had while it's on. */
	@GameTest(structure = GADGETS, maxTicks = 1200)
	public void aLatchHoldsWhatItHad(GameTestHelper helper) {
		Gadget latch = make("latch", "{\"bits\": 2}");
		helper.assertValueEqual(latch.ports().stream().filter(Port::input).map(Port::name).toList(), List.of("d0", "d1", "hold"), "inputs");
		GadgetRig.Rig rig = GadgetRig.build(helper, GadgetRig.Space.SMALL, latch, true);
		int wait = latch.settleTicks();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, rig.build()))
				.thenExecute(() -> GadgetRig.drive(rig, 0b001)).thenIdle(wait).thenExecute(() -> latched(helper, rig, Set.of("q0"), "d0 on"))
				.thenExecute(() -> GadgetRig.drive(rig, 0b101)).thenIdle(wait)
				.thenExecute(() -> GadgetRig.drive(rig, 0b110)).thenIdle(wait).thenExecute(() -> latched(helper, rig, Set.of("q0"), "held, d1 on"))
				.thenExecute(() -> GadgetRig.drive(rig, 0b010)).thenIdle(wait).thenExecute(() -> latched(helper, rig, Set.of("q1"), "let go"))
				.thenSucceed();
	}

	private static void latched(GameTestHelper helper, GadgetRig.Rig rig, Set<String> expected, String when) {
		for (int i = 0; i < rig.placed().size(); i++) {
			GadgetPlacement.Placed one = rig.placed().get(i);
			Set<String> on = new HashSet<>();
			for (Port p : one.ports()) {
				if (!p.input() && GadgetRig.on(rig, one, p.name())) {
					on.add(p.name());
				}
			}
			helper.assertValueEqual(on, expected, GadgetRig.FACINGS.get(i / 2) + ", " + when);
		}
	}

	/** A counter of 2 bits in every facing: after each pulse it reads the count, round from 3 to 0. */
	@GameTest(structure = GADGETS, maxTicks = 1800)
	public void aCounterCountsPulses(GameTestHelper helper) {
		Gadget counter = make("counter", "{\"bits\": 2}");
		GadgetRig.Rig rig = GadgetRig.build(helper, GadgetRig.Space.SMALL, counter, true);
		GameTestSequence sequence = helper.startSequence().thenWaitUntil(() -> ArenaBuilds.assertDone(helper, rig.build())).thenIdle(10)
				.thenExecute(() -> latched(helper, rig, Set.of(), "at the start"));
		for (int count = 1; count <= 5; count++) {
			Set<String> expected = new HashSet<>();
			for (int bit = 0; bit < 2; bit++) {
				if ((count % 4 >> bit & 1) == 1) {
					expected.add("q" + bit);
				}
			}
			int n = count;
			sequence = sequence.thenExecute(() -> GadgetRig.drive(rig, 1)).thenIdle(counter.settleTicks()).thenExecute(() -> GadgetRig.drive(rig, 0))
					.thenIdle(counter.settleTicks()).thenExecute(() -> latched(helper, rig, expected, "after " + n + " pulses"));
		}
		sequence.thenSucceed();
	}

	/** A 2 × 2 piston door in every facing: closed as built, open while open is powered, closed again after; no piston problem. */
	@GameTest(structure = GADGETS, maxTicks = 1200)
	public void aPistonDoorOpensWhilePowered(GameTestHelper helper) {
		Gadget door = make("piston_door", "{}");
		GadgetRig.Rig rig = GadgetRig.build(helper, GadgetRig.Space.SMALL, door, true);
		List<String> cells = List.of("doorway 0", "doorway 1", "doorway 2", "doorway 3");
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, rig.build()))
				.thenIdle(door.settleTicks())
				.thenExecute(() -> doorway(helper, rig, cells, false, "as built"))
				.thenExecute(() -> GadgetRig.drive(rig, 1)).thenIdle(door.settleTicks())
				.thenExecute(() -> doorway(helper, rig, cells, true, "open powered"))
				.thenExecute(() -> GadgetRig.drive(rig, 0)).thenIdle(door.settleTicks())
				.thenExecute(() -> doorway(helper, rig, cells, false, "open unpowered"))
				.thenExecute(() -> {
					String check = rig.build().reply("check_build");
					helper.assertFalse(check.isEmpty(), "check_build never answered");
					helper.assertFalse(check.contains("piston"), check);
				})
				.thenSucceed();
	}

	private static void doorway(GameTestHelper helper, GadgetRig.Rig rig, List<String> cells, boolean open, String when) {
		for (int i = 0; i < rig.placed().size(); i++) {
			for (String cell : cells) {
				BlockState state = GadgetRig.at(rig, rig.placed().get(i), cell);
				helper.assertValueEqual(state.isAir(), open, GadgetRig.FACINGS.get(i / 2) + ", " + when + ", " + cell + " is " + state + ": empty");
			}
		}
	}
}
