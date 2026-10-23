package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.GadgetRig.GADGETS_BIG;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonObject;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.test.ArenaBuilds.ArenaBuild;
import io.palmisano.aibuilder.world.JobScheduler;
import io.palmisano.aibuilder.world.UndoRunner;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * The calculator (the M9 plan's goal), built only from gadgets and wires through scripted tool calls: it adds the two
 * numbers set on its levers and shows the sum, undo takes it all back, and a refinement can wire gadgets an earlier build
 * placed.
 */
public class CalculatorGameTest {
	private static ArenaBuild build(GameTestHelper helper, List<JsonObject> calls) {
		return ArenaBuilds.start(helper, GadgetRig.frame(helper, GadgetRig.Space.BIG), GadgetRig.Space.BIG.allowed(), Calculator.script(calls));
	}

	private static List<JsonObject> all() {
		List<JsonObject> calls = new ArrayList<>(Calculator.gadgetCalls());
		calls.addAll(Calculator.wireCalls());
		return calls;
	}

	/** 9 + 7 shows 16 and 15 + 15 shows 30, read off the number's faces; its own test_redstone show says the same. */
	@GameTest(structure = GADGETS_BIG, maxTicks = 4000)
	public void theScriptedCalculatorAdds(GameTestHelper helper) {
		List<JsonObject> calls = all();
		calls.add(Calculator.testCall());
		ArenaBuild build = build(helper, calls);
		int settle = Calculator.settle();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> {
					String test = build.reply("test_redstone");
					helper.assertTrue(test.contains("the number at (-25, 1, 52) shows 16") && test.contains("the number at (-25, 1, 52) shows 30"), test);
				})
				.thenExecute(() -> Calculator.set(build, 9, 7)).thenIdle(settle)
				.thenExecute(() -> helper.assertValueEqual(Calculator.shows(build), "16", "9 + 7"))
				.thenExecute(() -> Calculator.set(build, 15, 15)).thenIdle(settle)
				.thenExecute(() -> helper.assertValueEqual(Calculator.shows(build), "30", "15 + 15"))
				.thenExecute(() -> Calculator.set(build, 0, 0)).thenIdle(settle)
				.thenExecute(() -> helper.assertValueEqual(Calculator.shows(build), "0", "0 + 0"))
				.thenSucceed();
	}

	/** Undo takes every block of it back: nothing is left in the box. */
	@GameTest(structure = GADGETS_BIG, maxTicks = 6000)
	public void undoingTheCalculatorLeavesNothing(GameTestHelper helper) {
		ArenaBuild build = build(helper, all());
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		Box box = GadgetRig.Space.BIG.allowed();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> {
					UndoRunner runner = new UndoRunner(build.level(), build.journal(), Tunables.BLOCKS_PER_TICK);
					JobScheduler.add(runner);
					undo.set(runner);
				})
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> {
					helper.assertValueEqual(undo.get().result().join().kept(), 0, "kept");
					for (int x = box.min().x(); x <= box.max().x(); x++) {
						for (int y = Math.max(0, box.min().y()); y <= box.max().y(); y++) {
							for (int z = box.min().z(); z <= box.max().z(); z++) {
								helper.assertTrue(build.state(x, y, z).isAir(), "left at " + new Pos(x, y, z) + ": " + build.state(x, y, z));
							}
						}
					}
				})
				.thenSucceed();
	}

	/** A first build places the gadgets; a refinement wires them, knowing their ports from the session; then it adds. */
	@GameTest(structure = GADGETS_BIG, maxTicks = 5000)
	public void aRefinementWiresToEarlierGadgets(GameTestHelper helper) {
		ArenaBuild first = build(helper, Calculator.gadgetCalls());
		AtomicReference<ArenaBuild> second = new AtomicReference<>();
		int settle = Calculator.settle();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, first))
				.thenExecute(() -> second.set(ArenaBuilds.refine(helper, first, GadgetRig.Space.BIG.allowed(), Calculator.script(Calculator.wireCalls()))))
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, second.get()))
				.thenExecute(() -> Calculator.set(second.get(), 9, 7)).thenIdle(settle)
				.thenExecute(() -> helper.assertValueEqual(Calculator.shows(second.get()), "16", "9 + 7, wired by the refinement"))
				.thenSucceed();
	}
}
