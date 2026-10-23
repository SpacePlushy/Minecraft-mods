package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.ArenaBuilds.ARENA;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.config.AiBuilderConfig;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.survey.BuildProblems;
import io.palmisano.aibuilder.test.ArenaBuilds.ArenaBuild;
import io.palmisano.aibuilder.world.BuildCheck;
import io.palmisano.aibuilder.world.JobScheduler;
import io.palmisano.aibuilder.world.McWorldReader;
import io.palmisano.aibuilder.world.Occupancy;
import io.palmisano.aibuilder.world.Positions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** check_build (spec §8): what it finds in a real world, and that it reads a big build over ticks. */
public class CheckBuildGameTest {
	private static final Logger LOGGER = LogUtils.getLogger();

	private static ArenaBuild start(GameTestHelper helper, String calls) {
		return ArenaBuilds.start(helper, Facing.SOUTH, "{\"calls\": [{\"tool\": \"submit_brief\", \"args\": {\"title\": \"check\", \"style\": \"test\"}},"
				+ calls + ",{\"tool\": \"check_build\", \"args\": {}},{\"tool\": \"finish\", \"args\": {\"summary\": \"checked\"}}]}",
				Occupancy.players(helper.getLevel()), Tunables.BLOCKS_PER_TICK);
	}

	/** check_build's answer, once the build is over; in the log too, to read what Claude would. */
	private static String report(GameTestHelper helper, ArenaBuild build) {
		helper.assertTrue(build.done(), "the build hasn't finished");
		String report = build.reply("check_build");
		LOGGER.info("check_build: {}", report);
		return report;
	}

	/** A torch on nothing, and sand over air: both would go the moment something next to them changes. */
	@GameTest(structure = ARENA, maxTicks = 1200, skyAccess = true)
	public void aTorchOnAirIsUnsupported(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 3, 4], "block": "torch"}, {"pos": [2, 3, 4], "block": "sand"},
				  {"pos": [4, 1, 4], "block": "torch"}]}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertTrue(report.contains("unsupported (2)") && report.contains("(0, 3, 4) torch") && report.contains("(2, 3, 4) sand"),
					"not the floating torch and sand: " + report);
			helper.assertFalse(report.contains("(4, 1, 4)"), "the torch on the floor: " + report);
		});
	}

	/** A closed room with no light is dark inside (monsters spawn at block light 0); a lit one isn't. */
	@GameTest(structure = ARENA, maxTicks = 1200, skyAccess = true)
	public void aDarkRoomIsFound(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"tool": "fill", "args": {"box": [-3, 1, 2, 1, 4, 6], "block": "stone", "mode": "hollow"}},
				{"tool": "fill", "args": {"box": [3, 1, 2, 7, 4, 6], "block": "stone", "mode": "hollow"}},
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [5, 1, 4], "block": "glowstone"}]}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertTrue(report.contains("dark (") && report.contains("(-1, 2, 4)"), "the dark room: " + report);
			helper.assertFalse(report.contains("(5, 2, 4)") || report.contains("(4, 2, 3)") || report.contains("(6, 2, 5)"), "the lit room: " + report);
		});
	}

	/**
	 * An overhang high up isn't a roof: out in the open, sky light still reaches the ground under it (the M5 live
	 * castle listed 184 "dark" spots in rings round its towers, under their battlements).
	 */
	@GameTest(structure = ARENA, maxTicks = 1200, skyAccess = true)
	public void anOverhangIsntARoof(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"tool": "fill", "args": {"box": [0, 1, 5, 0, 8, 5], "block": "stone"}},
				{"tool": "fill", "args": {"box": [-1, 9, 4, 1, 9, 6], "block": "stone"}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertFalse(report.contains("dark"), "the ground round a pillar, under its cap: " + report);
		});
	}

	/** A door with stone at head height on one side can't be walked through; the one next to it can. */
	@GameTest(structure = ARENA, maxTicks = 1200, skyAccess = true)
	public void aBlockedDoorIsFound(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"tool": "fill", "args": {"box": [-3, 1, 4, 3, 3, 4], "block": "stone"}},
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [-1, 1, 4], "block": "oak_door", "state": {"facing": "forward"}},
				  {"pos": [1, 1, 4], "block": "oak_door", "state": {"facing": "forward"}}, {"pos": [1, 2, 5], "block": "stone"}]}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertTrue(report.contains("door (1)") && report.contains("(1, 1, 4)"), "the blocked door: " + report);
			helper.assertFalse(report.contains("(-1, 1, 4)"), "the open door: " + report);
		});
	}

	/**
	 * Something no higher than a step at the feet (a carpet, a bottom slab, a closed trapdoor) is walked over: those
	 * doors are fine. A full block there isn't.
	 */
	@GameTest(structure = ARENA, maxTicks = 1200, skyAccess = true)
	public void aCarpetDoesntBlockADoor(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"tool": "fill", "args": {"box": [-6, 1, 4, 6, 3, 4], "block": "stone"}},
				{"tool": "set_blocks", "args": {"blocks": [
				  {"pos": [-4, 1, 4], "block": "oak_door", "state": {"facing": "forward"}}, {"pos": [-4, 1, 5], "block": "white_carpet"},
				  {"pos": [-1, 1, 4], "block": "oak_door", "state": {"facing": "forward"}},
				  {"pos": [-1, 1, 5], "block": "oak_slab", "state": {"type": "bottom"}},
				  {"pos": [2, 1, 4], "block": "oak_door", "state": {"facing": "forward"}},
				  {"pos": [2, 1, 3], "block": "oak_trapdoor", "state": {"half": "bottom", "open": "false"}},
				  {"pos": [5, 1, 4], "block": "oak_door", "state": {"facing": "forward"}}, {"pos": [5, 1, 5], "block": "stone"}]}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertTrue(report.contains("door (1)") && report.contains("(5, 1, 4)"), "only the door with stone at its feet: " + report);
		});
	}

	/**
	 * A slab step at the feet with a block two above: standing on the slab takes 2.3 blocks of room, so that door can't
	 * be walked through. A slab with nothing over it is fine, and so is a carpet under a block (the minors' review).
	 */
	@GameTest(structure = ARENA, maxTicks = 1200, skyAccess = true)
	public void aSlabStepUnderALowCeilingBlocksADoor(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"tool": "fill", "args": {"box": [-6, 1, 4, 6, 3, 4], "block": "stone"}},
				{"tool": "set_blocks", "args": {"blocks": [
				  {"pos": [-4, 1, 4], "block": "oak_door", "state": {"facing": "forward"}},
				  {"pos": [-4, 1, 5], "block": "oak_slab", "state": {"type": "bottom"}}, {"pos": [-4, 3, 5], "block": "stone"},
				  {"pos": [-1, 1, 4], "block": "oak_door", "state": {"facing": "forward"}},
				  {"pos": [-1, 1, 5], "block": "oak_slab", "state": {"type": "bottom"}},
				  {"pos": [2, 1, 4], "block": "oak_door", "state": {"facing": "forward"}},
				  {"pos": [2, 1, 5], "block": "white_carpet"}, {"pos": [2, 3, 5], "block": "stone"}]}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertTrue(report.contains("door (1)") && report.contains("(-4, 1, 4)"), "the door behind the slab step under a block: " + report);
			helper.assertFalse(report.contains("(-1, 1, 4)") || report.contains("(2, 1, 4)"), "a door that can be walked through: " + report);
		});
	}

	/**
	 * What the build placed while a check read (a retry joins a check a timeout left running) is read too before it
	 * answers: here a torch on nothing, journaled and written mid-check (the minors' review).
	 */
	@GameTest(structure = ARENA, maxTicks = 2400, skyAccess = true)
	public void aCheckReadsWhatWasPlacedWhileItRead(GameTestHelper helper) {
		ArenaBuild build = ArenaBuilds.start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "cube", "style": "test"}},
				  {"tool": "fill", "args": {"box": [-1, 1, 3, 1, 3, 5], "block": "stone"}},
				  {"tool": "finish", "args": {"summary": "a cube"}}
				]}""", Occupancy.players(helper.getLevel()), Tunables.BLOCKS_PER_TICK);
		AtomicReference<BuildCheck> check = new AtomicReference<>();
		Pos torch = new Pos(4, 3, 4);
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> {
					check.set(new BuildCheck(helper.getLevel(), build.frame(), ArenaBuilds.ALLOWED, build.journal(), 1));
					JobScheduler.add(check.get());
				})
				.thenIdle(5)
				.thenExecute(() -> {
					helper.assertFalse(check.get().result().isDone(), "the check was done before anything more was placed");
					Pos at = build.frame().toWorld(torch);
					build.journal().beforeWrite(at, Blocks.AIR.defaultBlockState(), null);
					helper.getLevel().setBlock(Positions.toBlockPos(at), Blocks.TORCH.defaultBlockState(), 18);
					build.journal().afterWrite(at, "minecraft:torch", 0);
				})
				.thenWaitUntil(() -> helper.assertTrue(check.get().result().isDone(), "the check hasn't finished"))
				.thenExecute(() -> {
					List<BuildProblems.Problem> problems = check.get().result().join();
					helper.assertTrue(problems.stream().anyMatch(p -> p.kind() == BuildProblems.Kind.UNSUPPORTED && p.pos().equals(torch)),
							"the torch placed while it read: " + problems);
				})
				.thenSucceed();
	}

	/** Nothing spawns on glass: a dark room with a glass floor has nowhere monsters could appear. */
	@GameTest(structure = ARENA, maxTicks = 1200, skyAccess = true)
	public void aGlassFloorIsntSpawningGround(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"tool": "fill", "args": {"box": [-3, 1, 2, 1, 4, 6], "block": "stone", "mode": "hollow"}},
				{"tool": "fill", "args": {"box": [-2, 1, 3, 0, 1, 5], "block": "glass"}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertFalse(report.contains("dark"), "the room with a glass floor: " + report);
		});
	}

	/** check_build called again while one is still reading (a retry after a timeout) waits on that one: one read. */
	@GameTest(structure = ARENA, maxTicks = 2400, skyAccess = true)
	public void twoChecksAtOnceShareOneRead(GameTestHelper helper) {
		// A problem to find, so each read's answer is a list of its own (an empty one is the JDK's one empty list).
		ArenaBuild build = ArenaBuilds.start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "slab roof", "style": "test"}},
				  {"tool": "fill", "args": {"box": [-3, 2, 3, -1, 2, 5], "block": "stone"}},
				  {"tool": "finish", "args": {"summary": "a roof too low"}}
				]}""", Occupancy.players(helper.getLevel()), Tunables.BLOCKS_PER_TICK);
		AtomicReference<CompletableFuture<List<BuildProblems.Problem>>> first = new AtomicReference<>();
		AtomicReference<CompletableFuture<List<BuildProblems.Problem>>> second = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> {
					McWorldReader world = new McWorldReader(helper.getLevel(), build.frame(), ArenaBuilds.ALLOWED, SizeTier.SMALL, () -> null,
							List.of(), build.journal(), AiBuilderConfig.DEFAULT);
					// From other threads, as the tools call it: the server thread does the reading.
					first.set(CompletableFuture.supplyAsync(world::check));
					second.set(CompletableFuture.supplyAsync(world::check));
				})
				.thenWaitUntil(() -> helper.assertTrue(first.get().isDone() && second.get().isDone(), "the checks haven't finished"))
				.thenExecute(() -> {
					helper.assertFalse(first.get().join().isEmpty(), "the low roof wasn't found");
					helper.assertTrue(first.get().join() == second.get().join(), "the second check read the build again");
				})
				.thenSucceed();
	}

	/** A ceiling one block over the floor leaves no room to walk; two blocks is fine. */
	@GameTest(structure = ARENA, maxTicks = 1200, skyAccess = true)
	public void aLowCeilingIsFound(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"tool": "fill", "args": {"box": [-3, 2, 3, -1, 2, 5], "block": "stone"}},
				{"tool": "fill", "args": {"box": [3, 3, 3, 5, 3, 5], "block": "stone"}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertTrue(report.contains("low (") && report.contains("(-2, 1, 4)"), "the low ceiling: " + report);
			helper.assertFalse(report.contains("(4, 1, 4)") || report.contains("(4, 2, 4)"), "the two-high one: " + report);
		});
	}

	/** Water with an open side, or air under it, would flow if anything disturbed it; a walled pool wouldn't. */
	@GameTest(structure = ARENA, maxTicks = 1200, skyAccess = true)
	public void aPoolEdgeSpills(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"tool": "fill", "args": {"box": [-4, 1, 3, -2, 1, 5], "block": "stone"}},
				{"tool": "fill", "args": {"box": [4, 2, 3, 6, 2, 5], "block": "stone"}},
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [-3, 1, 4], "block": "water"}, {"pos": [1, 1, 4], "block": "water"},
				  {"pos": [5, 2, 4], "block": "water"}]}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertTrue(report.contains("spill (2)") && report.contains("(1, 1, 4) water") && report.contains("(5, 2, 4) water"),
					"the open pool and the one over air: " + report);
			helper.assertFalse(report.contains("(-3, 1, 4)"), "the walled pool: " + report);
		});
	}

	/**
	 * A check reads at least one position a tick and stops at its budget, so a castle's worth never freezes the
	 * server (M5 Review Focus): with a budget of a nanosecond, 27 positions take 27 ticks or more.
	 */
	@GameTest(structure = ARENA, maxTicks = 2400, skyAccess = true)
	public void checkBuildRunsOverTicks(GameTestHelper helper) {
		ArenaBuild build = ArenaBuilds.start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "cube", "style": "test"}},
				  {"tool": "fill", "args": {"box": [-1, 1, 3, 1, 3, 5], "block": "stone"}},
				  {"tool": "finish", "args": {"summary": "a cube"}}
				]}""", Occupancy.players(helper.getLevel()), Tunables.BLOCKS_PER_TICK);
		AtomicReference<BuildCheck> check = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> {
					check.set(new BuildCheck(helper.getLevel(), build.frame(), ArenaBuilds.ALLOWED, build.journal(), 1));
					JobScheduler.add(check.get());
				})
				.thenWaitUntil(() -> helper.assertTrue(check.get().result().isDone(), "the check hasn't finished"))
				.thenExecute(() -> {
					List<BuildProblems.Problem> problems = check.get().result().join();
					helper.assertTrue(problems.isEmpty(), "a stone cube has problems: " + problems);
					helper.assertTrue(check.get().ticks() >= 27, "27 positions in " + check.get().ticks() + " ticks");
				})
				.thenSucceed();
	}
}
