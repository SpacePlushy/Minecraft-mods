package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.GadgetRig.GADGETS;
import static io.palmisano.aibuilder.test.GadgetRig.GADGETS_BIG;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
}
