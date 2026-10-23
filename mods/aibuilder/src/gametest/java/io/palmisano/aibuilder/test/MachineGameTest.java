package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.ArenaBuilds.ARENA;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.mojang.logging.LogUtils;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.tools.RedstoneSteps;
import io.palmisano.aibuilder.test.ArenaBuilds.ArenaBuild;
import io.palmisano.aibuilder.world.JobScheduler;
import io.palmisano.aibuilder.world.Occupancy;
import io.palmisano.aibuilder.world.UndoRunner;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.slf4j.Logger;

/** Redstone in a real world (spec v2 §5): what a machine may touch, its power-up, its tests and its undo. */
public class MachineGameTest {
	private static final Logger LOGGER = LogUtils.getLogger();

	/** A build of these calls that ends with check_build, facing south. */
	private static ArenaBuild checked(GameTestHelper helper, String calls) {
		return ArenaBuilds.start(helper, Facing.SOUTH, "{\"calls\": [{\"tool\": \"submit_brief\", \"args\": {\"title\": \"machine\", \"style\": \"test\"}},"
				+ calls + ",{\"tool\": \"check_build\", \"args\": {}},{\"tool\": \"finish\", \"args\": {\"summary\": \"checked\"}}]}",
				Occupancy.players(helper.getLevel()), Tunables.BLOCKS_PER_TICK);
	}

	/** check_build's answer, once the build is over. */
	private static String report(GameTestHelper helper, ArenaBuild build) {
		ArenaBuilds.assertDone(helper, build);
		String report = build.reply("check_build");
		LOGGER.info("check_build: {}", report);
		return report;
	}

	/** A build of these calls, facing south, that ends; a spot is taken where {@code occupancy} says. */
	private static ArenaBuild built(GameTestHelper helper, String calls, Occupancy occupancy) {
		return ArenaBuilds.start(helper, Facing.SOUTH, "{\"calls\": [{\"tool\": \"submit_brief\", \"args\": {\"title\": \"machine\", \"style\": \"test\"}},"
				+ calls + ",{\"tool\": \"finish\", \"args\": {\"summary\": \"built\"}}]}", occupancy, Tunables.BLOCKS_PER_TICK);
	}

	private static ArenaBuild built(GameTestHelper helper, String calls) {
		return built(helper, calls, Occupancy.players(helper.getLevel()));
	}

	/** Flips the lever at a local position, as a player's click does. */
	private static void pull(ArenaBuild build, Pos lever) {
		BlockPos pos = build.world(lever);
		((LeverBlock) Blocks.LEVER).pull(build.level().getBlockState(pos), build.level(), pos, null);
	}

	/** A block of the world's (the player's, or the ground), there before the build, at a local position. */
	private static void world(GameTestHelper helper, Pos local, BlockState state) {
		helper.getLevel().setBlock(ArenaBuilds.worldPos(helper, Facing.SOUTH, local), state, 3);
	}

	/** A piston whose push would move a block that isn't the build's (here the player's planks) is a piston problem. */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void aPistonPushingAPlayersBlockIsAProblem(GameTestHelper helper) {
		world(helper, new Pos(0, 1, 6), Blocks.OAK_PLANKS.defaultBlockState());
		ArenaBuild build = checked(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "piston", "state": {"facing": "forward"}},
				  {"pos": [0, 1, 5], "block": "stone"}]}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertTrue(report.contains("piston (1)") && report.contains("(0, 1, 4) piston") && report.contains("oak_planks at (0, 1, 6)"),
					"the piston that would push the planks: " + report);
		});
	}

	/** Slime and honey carry what they touch along (a flying machine leaves the box): a piston may never push them. */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void aPistonThatWouldPushSlimeIsAProblem(GameTestHelper helper) {
		ArenaBuild build = checked(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "sticky_piston", "state": {"facing": "forward"}},
				  {"pos": [0, 1, 5], "block": "slime_block"}]}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertTrue(report.contains("piston (1)") && report.contains("(0, 1, 4) sticky_piston") && report.contains("slime_block"),
					"the piston that would push slime: " + report);
		});
	}

	/** A push that would move the build's own block past the allowed box is a piston problem. */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void aPistonPushingOutOfTheBoxIsAProblem(GameTestHelper helper) {
		ArenaBuild build = checked(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 14], "block": "piston", "state": {"facing": "forward"}},
				  {"pos": [0, 1, 15], "block": "stone"}]}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertTrue(report.contains("piston (1)") && report.contains("(0, 1, 14) piston") && report.contains("(0, 1, 16)"),
					"the piston that would push past the box: " + report);
		});
	}

	/** The build's lever two blocks from the world's TNT could set it off: powers_outside. */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void aLeverBesideTheWorldsTntIsAProblem(GameTestHelper helper) {
		world(helper, new Pos(2, 1, 4), Blocks.TNT.defaultBlockState());
		ArenaBuild build = checked(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward"}}]}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertTrue(report.contains("powers_outside (1)") && report.contains("(0, 1, 4) lever") && report.contains("tnt at (2, 1, 4)"),
					"the lever beside the TNT: " + report);
		});
	}

	/** A lever placed on, with a lamp beside it: the lamp is lit once the build ends, as if it were built by hand. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void aLeverOnLightsItsLampOnceTheBuildEnds(GameTestHelper helper) {
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward",
				  "powered": "true"}}, {"pos": [1, 1, 4], "block": "redstone_lamp"}]}}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			helper.assertTrue(build.state(1, 1, 4).getValue(RedstoneLampBlock.LIT), "the lamp beside the lever that's on is dark");
		});
	}

	/** A torch on the front of a block lights its lamp until the lever on the block is on: a NOT gate, working as by hand. */
	@GameTest(structure = ARENA, maxTicks = 600)
	public void aTorchNotGateReadsRight(GameTestHelper helper) {
		Pos lever = new Pos(0, 2, 4);
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "stone"},
				  {"pos": [0, 2, 4], "block": "lever", "state": {"face": "floor", "facing": "forward"}},
				  {"pos": [0, 1, 5], "block": "redstone_wall_torch", "state": {"facing": "forward"}}, {"pos": [0, 1, 6], "block": "redstone_lamp"}]}}""");
		helper.startSequence()
				.thenWaitUntil(() -> {
					ArenaBuilds.assertDone(helper, build);
					helper.assertTrue(build.state(0, 1, 6).getValue(RedstoneLampBlock.LIT), "the lamp is dark with the lever off");
				})
				.thenExecute(() -> pull(build, lever))
				.thenWaitUntil(() -> helper.assertFalse(build.state(0, 1, 6).getValue(RedstoneLampBlock.LIT), "the lamp is lit with the lever on"))
				.thenExecute(() -> pull(build, lever))
				.thenWaitUntil(() -> helper.assertTrue(build.state(0, 1, 6).getValue(RedstoneLampBlock.LIT), "the lamp is dark with the lever off again"))
				.thenSucceed();
	}

	/**
	 * The pass touches only redstone and what's beside it: the build's water, with open sides, stays still, and its sand
	 * stays where it was placed. (Sand over air falls at any build's end, as it would for a player: the shape pass.)
	 */
	@GameTest(structure = ARENA, maxTicks = 600)
	public void waterAndSandStayPut(GameTestHelper helper) {
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward",
				  "powered": "true"}}, {"pos": [1, 1, 4], "block": "redstone_lamp"}, {"pos": [5, 1, 4], "block": "stone"}, {"pos": [5, 2, 4], "block": "sand"},
				  {"pos": [-5, 1, 4], "block": "water"}]}}""");
		helper.startSequence()
				.thenWaitUntil(() -> {
					ArenaBuilds.assertDone(helper, build);
					helper.assertTrue(build.state(1, 1, 4).getValue(RedstoneLampBlock.LIT), "the lamp is dark: no pass ran");
				})
				.thenIdle(40)
				.thenExecute(() -> {
					helper.assertTrue(build.state(5, 2, 4).is(Blocks.SAND), "the sand moved: " + build.state(5, 2, 4));
					for (Pos beside : new Pos[] {new Pos(-6, 1, 4), new Pos(-4, 1, 4), new Pos(-5, 1, 3), new Pos(-5, 1, 5)}) {
						helper.assertTrue(build.state(beside.x(), beside.y(), beside.z()).isAir(), "the water flowed to " + beside);
					}
				})
				.thenSucceed();
	}

	/** A player where a piston of the build would push: the machine waits to start until they've stepped out of the way. */
	@GameTest(structure = ARENA, maxTicks = 600)
	public void thePowerUpWaitsForAPlayerInAPistonsWay(GameTestHelper helper) {
		BlockPos inTheWay = ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(0, 1, 6));
		AtomicBoolean stillThere = new AtomicBoolean(true);
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "sticky_piston", "state": {"facing": "forward"}},
				  {"pos": [0, 1, 5], "block": "stone"}, {"pos": [-1, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward",
				  "powered": "true"}}]}}""", pos -> pos.equals(inTheWay) && stillThere.get());
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(build.job().waitingForPlayer() != null, "the machine isn't waiting for the player"))
				.thenIdle(10)
				.thenExecute(() -> {
					helper.assertFalse(build.state(0, 1, 4).getValue(PistonBaseBlock.EXTENDED), "the piston pushed toward the player");
					helper.assertFalse(build.done(), "the build ended while the player was in the way");
					stillThere.set(false);
				})
				.thenWaitUntil(() -> {
					ArenaBuilds.assertDone(helper, build);
					helper.assertTrue(build.state(0, 1, 4).getValue(PistonBaseBlock.EXTENDED), "the piston didn't start once the way was clear");
				})
				.thenSucceed();
	}

	/** A lever of the build beside the world's TNT is taken out before anything is powered: the TNT stays as it was. */
	@GameTest(structure = ARENA, maxTicks = 600)
	public void aRuleBreakerIsTakenOut(GameTestHelper helper) {
		world(helper, new Pos(1, 1, 4), Blocks.TNT.defaultBlockState());
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward",
				  "powered": "true"}}]}}""");
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenIdle(20)
				.thenExecute(() -> {
					helper.assertTrue(build.state(0, 1, 4).isAir(), "the lever beside the TNT is still there: " + build.state(0, 1, 4));
					helper.assertTrue(build.state(1, 1, 4).is(Blocks.TNT), "the TNT went: " + build.state(1, 1, 4));
					helper.assertValueEqual(build.job().takenOut(), List.of(build.world(new Pos(0, 1, 4))), "what was taken out");
				})
				.thenSucceed();
	}

	/**
	 * Undo of a piston door left open: its lever goes back as it was placed (off), the door closes, and then every
	 * block goes back, with no piston head or moved block left behind (spec v2 §5.1).
	 */
	@GameTest(structure = ARENA, maxTicks = 800)
	public void undoOfAnOpenPistonDoorClosesItFirst(GameTestHelper helper) {
		Pos lever = new Pos(-1, 1, 4);
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "sticky_piston", "state": {"facing": "forward"}},
				  {"pos": [0, 1, 5], "block": "stone"}, {"pos": [-1, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward"}}]}}""");
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> pull(build, lever))
				.thenWaitUntil(() -> helper.assertTrue(build.state(0, 1, 6).is(Blocks.STONE), "the door didn't open: " + build.state(0, 1, 6)))
				.thenExecute(() -> {
					UndoRunner runner = new UndoRunner(build.level(), build.journal(), Tunables.BLOCKS_PER_TICK, build.animations());
					JobScheduler.add(runner);
					undo.set(runner);
				})
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> {
					for (int z = 4; z <= 6; z++) {
						helper.assertTrue(build.state(0, 1, z).isAir(), "left at (0, 1, " + z + "): " + build.state(0, 1, z));
					}
					helper.assertTrue(build.state(-1, 1, 4).isAir(), "the lever is still there: " + build.state(-1, 1, 4));
				})
				.thenSucceed();
	}

	/** test_redstone flips the build's lever, reads its lamp dark then lit, and puts the lever back afterwards. */
	@GameTest(structure = ARENA, maxTicks = 800)
	public void testRedstoneFlipsALeverReadsALampAndPutsItBack(GameTestHelper helper) {
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward"}},
				  {"pos": [1, 1, 4], "block": "redstone_lamp"}]}},
				{"tool": "test_redstone", "args": {"steps": [{"read": [[1, 1, 4]]}, {"set": [0, 1, 4], "on": true}, {"wait": 4},
				  {"read": [[1, 1, 4]]}]}}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			String reply = build.reply("test_redstone");
			int dark = reply.indexOf("(1, 1, 4) redstone_lamp[lit=false]");
			int lit = reply.indexOf("(1, 1, 4) redstone_lamp[lit=true]");
			helper.assertTrue(dark >= 0 && lit > dark, "not dark, then lit: " + reply);
			helper.assertFalse(build.state(0, 1, 4).getValue(LeverBlock.POWERED), "the lever wasn't put back");
		});
	}

	/** test_redstone flips only the build's own levers: one of the world's is refused, and stays as it was. */
	@GameTest(structure = ARENA, maxTicks = 800)
	public void onlyTheBuildsOwnLeversFlip(GameTestHelper helper) {
		world(helper, new Pos(4, 1, 4), Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "redstone_lamp"}]}},
				{"tool": "test_redstone", "args": {"steps": [{"set": [4, 1, 4], "on": true}]}}""");
		helper.succeedWhen(() -> {
			helper.assertTrue(build.done(), "the build hasn't finished");
			String reply = build.reply("test_redstone");
			helper.assertTrue(reply.contains("(4, 1, 4)") && reply.contains("isn't a lever the build placed"), reply);
			helper.assertFalse(build.state(4, 1, 4).getValue(LeverBlock.POWERED), "the world's lever was flipped");
		});
	}

	/** A refinement's test sees the whole machine: the first build's lever lights the lamp the refinement added. */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void aRefinementsTestSeesTheWholeMachine(GameTestHelper helper) {
		ArenaBuild first = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward"}},
				  {"pos": [1, 1, 4], "block": "redstone_wire"}, {"pos": [2, 1, 4], "block": "redstone_wire"}]}}""");
		AtomicReference<ArenaBuild> second = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, first))
				.thenExecute(() -> second.set(ArenaBuilds.refine(helper, first, """
						{"calls": [
						  {"tool": "submit_brief", "args": {"title": "a lamp at the end", "style": "test"}},
						  {"tool": "set_blocks", "args": {"blocks": [{"pos": [3, 1, 4], "block": "redstone_lamp"}]}},
						  {"tool": "test_redstone", "args": {"steps": [{"set": [0, 1, 4], "on": true}, {"wait": 6}, {"read": [[3, 1, 4]]}]}},
						  {"tool": "finish", "args": {"summary": "a lamp the lever lights"}}
						]}""")))
				.thenWaitUntil(() -> {
					ArenaBuilds.assertDone(helper, second.get());
					String reply = second.get().reply("test_redstone");
					helper.assertTrue(reply.contains("(3, 1, 4) redstone_lamp[lit=true]"), "the lamp at the end of the earlier dust: " + reply);
				})
				.thenSucceed();
	}

	/** A machine is started once: the first test starts it, the second has nothing left to start (a clock would double). */
	@GameTest(structure = ARENA, maxTicks = 800)
	public void aMachineIsStartedOnce(GameTestHelper helper) {
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward"}},
				  {"pos": [1, 1, 4], "block": "redstone_lamp"}]}},
				{"tool": "test_redstone", "args": {"steps": [{"read": [[1, 1, 4]]}]}},
				{"tool": "test_redstone", "args": {"steps": [{"read": [[1, 1, 4]]}]}}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			List<String> replies = build.replies("test_redstone");
			helper.assertValueEqual(replies.size(), 2, "tests");
			helper.assertTrue(replies.get(0).contains("Started 2 redstone pieces"), replies.get(0));
			helper.assertFalse(replies.get(1).contains("Started"), replies.get(1));
		});
	}

	/** A player where a piston of the build would push: the test waits for them, then refuses, and nothing moved. */
	@GameTest(structure = ARENA, maxTicks = 3000)
	public void aTestWaitsForAPlayerInAPistonsWay(GameTestHelper helper) {
		BlockPos inTheWay = ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(0, 1, 6));
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "sticky_piston", "state": {"facing": "forward"}},
				  {"pos": [0, 1, 5], "block": "stone"}, {"pos": [-1, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward"}}]}},
				{"tool": "test_redstone", "args": {"steps": [{"set": [-1, 1, 4], "on": true}, {"wait": 4}]}}""", pos -> pos.equals(inTheWay));
		helper.succeedWhen(() -> {
			helper.assertTrue(build.done(), "the build hasn't finished");
			String reply = build.reply("test_redstone");
			helper.assertTrue(reply.contains("in the way of the piston at (0, 1, 4)"), reply);
			helper.assertFalse(build.state(0, 1, 4).getValue(PistonBaseBlock.EXTENDED), "the piston pushed toward the player");
		});
	}

	/**
	 * Undo of a machine whose piston stands extended at rest (a door a torch holds shut): the piston goes back retracted,
	 * pulling its block home, so once the blocks are restored nothing is left: no piston head, no moved block.
	 */
	@GameTest(structure = ARENA, maxTicks = 800)
	public void undoOfAPistonHeldOutLeavesNothing(GameTestHelper helper) {
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "sticky_piston", "state": {"facing": "up"}},
				  {"pos": [0, 2, 4], "block": "cobblestone"}, {"pos": [2, 1, 4], "block": "stone"},
				  {"pos": [1, 1, 4], "block": "redstone_wall_torch", "state": {"facing": "left"}},
				  {"pos": [2, 2, 4], "block": "lever", "state": {"face": "floor", "facing": "forward"}}]}}""");
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> {
					ArenaBuilds.assertDone(helper, build);
					helper.assertTrue(build.state(0, 3, 4).is(Blocks.COBBLESTONE), "the torch didn't push the block up: " + build.state(0, 3, 4));
				})
				.thenExecute(() -> {
					UndoRunner runner = new UndoRunner(build.level(), build.journal(), Tunables.BLOCKS_PER_TICK, build.animations());
					JobScheduler.add(runner);
					undo.set(runner);
				})
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> {
					for (Pos at : List.of(new Pos(0, 1, 4), new Pos(0, 2, 4), new Pos(0, 3, 4), new Pos(1, 1, 4), new Pos(2, 1, 4), new Pos(2, 2, 4))) {
						BlockState left = build.state(at.x(), at.y(), at.z());
						helper.assertTrue(left.isAir(), "left at " + at + ": " + left);
					}
				})
				.thenSucceed();
	}

	/**
	 * A sticky piston that pushes the build's own block into the build's own air, its lever, and a lamp of the build's
	 * beside them: a machine that touches only its own build is fine.
	 */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void aPistonDoorOfTheBuildsOwnIsFine(GameTestHelper helper) {
		ArenaBuild build = checked(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "sticky_piston", "state": {"facing": "forward"}},
				  {"pos": [0, 1, 5], "block": "stone"}, {"pos": [-1, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward"}},
				  {"pos": [1, 1, 3], "block": "redstone_lamp"}]}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertFalse(report.contains("piston (") || report.contains("powers_outside"), "a machine of the build's own: " + report);
		});
	}

	/** The build's door, trapdoor and fence gate and a lectern beside the world's door give no power: no problem, nothing taken out. */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void doorsBesideTheWorldsDoorAreFine(GameTestHelper helper) {
		worldDoor(helper, new Pos(3, 1, 4));
		ArenaBuild build = checked(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [1, 1, 4], "block": "oak_door", "state": {"facing": "forward", "half": "lower"}},
				  {"pos": [2, 1, 6], "block": "oak_trapdoor", "state": {"facing": "forward"}},
				  {"pos": [4, 1, 5], "block": "oak_fence_gate", "state": {"facing": "forward"}},
				  {"pos": [3, 1, 2], "block": "lectern", "state": {"facing": "forward"}}]}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertFalse(report.contains("powers_outside"), "a block that gives no power is a problem: " + report);
			helper.assertValueEqual(build.job().takenOut(), List.of(), "what was taken out");
			helper.assertTrue(build.state(1, 1, 4).is(Blocks.OAK_DOOR) && build.state(1, 2, 4).is(Blocks.OAK_DOOR), "the build's door went");
		});
	}

	/** test_redstone won't press the build's button beside the world's door (it would open it); the button stays as built. */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void aTestWontPressAButtonBesideTheWorldsDoor(GameTestHelper helper) {
		worldDoor(helper, new Pos(2, 1, 4));
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "stone_button", "state": {"face": "floor", "facing": "forward"}}]}},
				{"tool": "test_redstone", "args": {"steps": [{"press": [0, 1, 4]}, {"wait": 4}]}}""");
		helper.succeedWhen(() -> {
			helper.assertTrue(build.done(), "the build hasn't finished");
			String reply = build.reply("test_redstone");
			helper.assertTrue(reply.contains("won't press") && reply.contains("oak_door at (2, 1, 4)"), reply);
			helper.assertFalse(build.state(2, 1, 4).getValue(DoorBlock.OPEN), "the world's door opened");
			helper.assertValueEqual(build.job().takenOut(), List.of(), "what was taken out");
		});
	}

	/** A dropper of the build facing into a dispenser would feed it what the dispenser rule keeps out: feeds_dispenser. */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void aDropperFeedingADispenserIsAProblem(GameTestHelper helper) {
		ArenaBuild build = checked(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "dispenser", "state": {"facing": "forward"}},
				  {"pos": [0, 1, 3], "block": "dropper", "state": {"facing": "forward"}}]}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertTrue(report.contains("feeds_dispenser (1)") && report.contains("(0, 1, 3) dropper") && report.contains("dispenser at (0, 1, 4)"),
					"the dropper feeding the dispenser: " + report);
		});
	}

	/** A sticky piston pulls back what's in front of its head: with air in front, the world's planks two ahead would come. */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void aStickyPistonThatWouldPullAPlayersBlockIsAProblem(GameTestHelper helper) {
		world(helper, new Pos(0, 1, 6), Blocks.OAK_PLANKS.defaultBlockState());
		ArenaBuild build = checked(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "sticky_piston", "state": {"facing": "forward"}}]}}""");
		helper.succeedWhen(() -> {
			String report = report(helper, build);
			helper.assertTrue(report.contains("piston (1)") && report.contains("(0, 1, 4) sticky_piston") && report.contains("pull oak_planks at (0, 1, 6)"),
					"the sticky piston that would pull the planks: " + report);
		});
	}

	/** A machine that moves its own blocks (a torch holds a piston out, pushing a lamp up) passes its next test and keeps its pieces. */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void aMachineThatMovedItsOwnBlocksPassesItsChecks(GameTestHelper helper) {
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "sticky_piston", "state": {"facing": "up"}},
				  {"pos": [0, 2, 4], "block": "redstone_lamp"}, {"pos": [2, 1, 4], "block": "stone"},
				  {"pos": [1, 1, 4], "block": "redstone_wall_torch", "state": {"facing": "left"}}]}},
				{"tool": "test_redstone", "args": {"steps": [{"wait": 4}, {"read": [[0, 3, 4]]}]}},
				{"tool": "test_redstone", "args": {"steps": [{"wait": 2}, {"read": [[0, 3, 4]]}]}}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			List<String> replies = build.replies("test_redstone");
			helper.assertTrue(replies.size() == 2 && replies.get(1).contains("(0, 3, 4) redstone_lamp"), "the second test: " + replies);
			helper.assertValueEqual(build.job().takenOut(), List.of(), "what was taken out");
			helper.assertTrue(build.state(1, 1, 4).is(Blocks.REDSTONE_WALL_TORCH), "the torch went: " + build.state(1, 1, 4));
		});
	}

	/** Redstone written where a test already started things (stone, then a redstone block and a lamp) still starts when the build ends. */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void redstoneWrittenWhereATestRanStillStarts(GameTestHelper helper) {
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "stone"}, {"pos": [1, 1, 4], "block": "stone"}]}},
				{"tool": "test_redstone", "args": {"steps": [{"wait": 1}]}},
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "redstone_block"}, {"pos": [1, 1, 4], "block": "redstone_lamp"}]}}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			helper.assertTrue(build.state(1, 1, 4).getValue(RedstoneLampBlock.LIT), "the lamp beside the redstone block is dark");
		});
	}

	/** An observer watching a block that doesn't change never fires: not in a test, not when the build ends (the bulb it feeds stays off). */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void anObserverIsntFiredByTheBuildOrItsTests(GameTestHelper helper) {
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 5], "block": "stone"},
				  {"pos": [0, 1, 4], "block": "observer", "state": {"facing": "forward"}}, {"pos": [0, 1, 3], "block": "waxed_copper_bulb"}]}},
				{"tool": "test_redstone", "args": {"steps": [{"wait": 6}, {"read": [[0, 1, 3]]}]}},
				{"tool": "test_redstone", "args": {"steps": [{"wait": 6}, {"read": [[0, 1, 3]]}]}}""");
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenIdle(10)
				.thenExecute(() -> {
					for (String reply : build.replies("test_redstone")) {
						helper.assertTrue(reply.contains("(0, 1, 3) waxed_copper_bulb[lit=false"), "the bulb flipped in a test: " + reply);
					}
					helper.assertFalse(build.state(0, 1, 3).getValue(CopperBulbBlock.LIT), "the bulb flipped when the build ended");
				})
				.thenSucceed();
	}

	/** A player who steps into a piston's way during a test holds the next step back; still there, the test stops, and nothing pushed them. */
	@GameTest(structure = ARENA, maxTicks = 3000)
	public void aTestWaitsWhenAPlayerStepsIntoAPistonsWay(GameTestHelper helper) {
		BlockPos inTheWay = ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(0, 1, 6));
		AtomicBoolean there = new AtomicBoolean(false);
		AtomicBoolean pushed = new AtomicBoolean(false);
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "sticky_piston", "state": {"facing": "forward"}},
				  {"pos": [0, 1, 5], "block": "stone"}, {"pos": [-1, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward"}}]}},
				{"tool": "test_redstone", "args": {"steps": [{"set": [-1, 1, 4], "on": true}, {"wait": 10}, {"set": [-1, 1, 4], "on": false},
				  {"wait": 20}, {"set": [-1, 1, 4], "on": true}, {"wait": 4}]}}""", pos -> pos.equals(inTheWay) && there.get());
		helper.onEachTick(() -> {
			if (there.get() && !helper.getLevel().getBlockState(inTheWay).isAir()) {
				pushed.set(true);
			}
		});
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(build.state(0, 1, 6).is(Blocks.STONE), "the door didn't open"))
				.thenWaitUntil(() -> helper.assertTrue(build.state(0, 1, 5).is(Blocks.STONE), "the door didn't close"))
				.thenExecute(() -> there.set(true))
				.thenWaitUntil(() -> helper.assertTrue(build.done(), "the build hasn't finished"))
				.thenExecute(() -> {
					helper.assertFalse(pushed.get(), "the piston pushed where the player stood");
					String reply = build.reply("test_redstone");
					helper.assertTrue(reply.contains("in the way of the piston at (0, 1, 4)"), reply);
				})
				.thenSucceed();
	}

	/** Undo with a player where a piston would push: the levers aren't flipped back (that would push them); still nothing is left. */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void undoWithAPlayerInAPistonsWayPushesNoOne(GameTestHelper helper) {
		BlockPos inTheWay = ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(0, 1, 6));
		AtomicBoolean there = new AtomicBoolean(false);
		AtomicBoolean pushed = new AtomicBoolean(false);
		Pos lever = new Pos(-1, 1, 4);
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "sticky_piston", "state": {"facing": "forward"}},
				  {"pos": [0, 1, 5], "block": "stone"}, {"pos": [-1, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward",
				  "powered": "true"}}]}}""");
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		helper.onEachTick(() -> {
			if (there.get() && !helper.getLevel().getBlockState(inTheWay).isAir()) {
				pushed.set(true);
			}
		});
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenWaitUntil(() -> helper.assertTrue(build.state(0, 1, 6).is(Blocks.STONE), "the door didn't open as built"))
				.thenExecute(() -> pull(build, lever))
				.thenWaitUntil(() -> helper.assertTrue(build.state(0, 1, 5).is(Blocks.STONE) && build.state(0, 1, 6).isAir(), "the door didn't close"))
				.thenExecute(() -> {
					there.set(true);
					UndoRunner runner = new UndoRunner(build.level(), build.journal(), Tunables.BLOCKS_PER_TICK, build.animations(),
							pos -> pos.equals(inTheWay) && there.get());
					JobScheduler.add(runner);
					undo.set(runner);
				})
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> {
					helper.assertFalse(pushed.get(), "undo pushed where the player stood");
					for (int z = 4; z <= 5; z++) {
						helper.assertTrue(build.state(0, 1, z).isAir(), "left at (0, 1, " + z + "): " + build.state(0, 1, z));
					}
					helper.assertTrue(build.state(-1, 1, 4).isAir(), "the lever is still there: " + build.state(-1, 1, 4));
				})
				.thenSucceed();
	}

	/** Two tests at once (Claude may send them together): one runs, the other is refused, and the lever ends as built. */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void twoTestsAtOnceRunOneAtATime(GameTestHelper helper) {
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward"}},
				  {"pos": [1, 1, 4], "block": "redstone_lamp"}]}}""");
		AtomicReference<CompletableFuture<String>> shorter = new AtomicReference<>();
		AtomicReference<CompletableFuture<String>> longer = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> {
					Pos lever = new Pos(0, 1, 4);
					shorter.set(CompletableFuture.supplyAsync(() -> build.world().testRedstone(List.of(new RedstoneSteps.Set(lever, true), new RedstoneSteps.Wait(5)))));
					longer.set(CompletableFuture.supplyAsync(() -> build.world().testRedstone(List.of(new RedstoneSteps.Set(lever, true), new RedstoneSteps.Wait(20)))));
				})
				.thenWaitUntil(() -> helper.assertTrue(shorter.get().isDone() && longer.get().isDone(), "the tests haven't finished"))
				.thenIdle(Tunables.TEST_SETTLE_TICKS)
				.thenExecute(() -> {
					helper.assertFalse(build.state(0, 1, 4).getValue(LeverBlock.POWERED), "the lever wasn't put back as built");
					helper.assertTrue(shorter.get().isCompletedExceptionally() != longer.get().isCompletedExceptionally(), "both tests ran, or neither did");
				})
				.thenSucceed();
	}

	/** A build cancelled while its test waits: the test stops and puts its lever back, not long after. */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void aCancelledBuildsTestStops(GameTestHelper helper) {
		ArenaBuild build = built(helper, """
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward"}},
				  {"pos": [1, 1, 4], "block": "redstone_lamp"}]}},
				{"tool": "test_redstone", "args": {"steps": [{"set": [0, 1, 4], "on": true}, {"wait": 400}]}}""");
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(build.state(0, 1, 4).is(Blocks.LEVER) && build.state(0, 1, 4).getValue(LeverBlock.POWERED),
						"the test hasn't flipped the lever"))
				.thenExecute(build::cancel)
				.thenIdle(30)
				.thenExecute(() -> {
					BlockState lever = build.state(0, 1, 4);
					helper.assertTrue(lever.is(Blocks.LEVER) && !lever.getValue(LeverBlock.POWERED), "the cancelled build's test still holds its lever: " + lever);
				})
				.thenSucceed();
	}

	/** Undo of a machine whose block entities changed as it ran (a comparator reading a chest, a dispenser that fired) still takes them back. */
	@GameTest(structure = ARENA, maxTicks = 1200)
	public void undoTakesBackBlockEntitiesTheMachineChanged(GameTestHelper helper) {
		ArenaBuild build = built(helper, """
				{"tool": "place_block_entity", "args": {"pos": [0, 1, 4], "block": "chest", "container": {"items": [{"item": "cobblestone", "count": 32}]}}},
				{"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 5], "block": "comparator", "state": {"facing": "back"}},
				  {"pos": [0, 1, 6], "block": "redstone_lamp"},
				  {"pos": [3, 1, 4], "block": "lever", "state": {"face": "floor", "facing": "forward", "powered": "true"}}]}},
				{"tool": "place_block_entity", "args": {"pos": [4, 1, 4], "block": "dispenser", "state": {"facing": "forward"},
				  "container": {"items": [{"item": "cobblestone", "count": 5}]}}}""");
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenWaitUntil(() -> helper.assertTrue(build.state(0, 1, 6).getValue(RedstoneLampBlock.LIT), "the comparator didn't light the lamp"))
				.thenIdle(10)
				.thenExecute(() -> {
					UndoRunner runner = new UndoRunner(build.level(), build.journal(), Tunables.BLOCKS_PER_TICK, build.animations());
					JobScheduler.add(runner);
					undo.set(runner);
				})
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> {
					helper.assertTrue(build.state(0, 1, 5).isAir(), "the comparator is still there: " + build.state(0, 1, 5));
					helper.assertTrue(build.state(4, 1, 4).isAir(), "the dispenser is still there: " + build.state(4, 1, 4));
					helper.assertTrue(build.state(0, 1, 4).isAir(), "the chest is still there: " + build.state(0, 1, 4));
				})
				.thenSucceed();
	}

	/** The world's oak door, both halves, at a local position. */
	private static void worldDoor(GameTestHelper helper, Pos lower) {
		world(helper, lower, Blocks.OAK_DOOR.defaultBlockState());
		world(helper, new Pos(lower.x(), lower.y() + 1, lower.z()), Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
	}
}
