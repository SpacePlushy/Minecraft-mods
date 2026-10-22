package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.ArenaBuilds.ARENA;

import java.util.concurrent.atomic.AtomicBoolean;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.test.ArenaBuilds.ArenaBuild;
import io.palmisano.aibuilder.world.Occupancy;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** The placement engine in a real world: rotation, pass order, the strict flags, waiting for players, finalize. */
public class PlacementGameTest {
	// The demo house once per facing. The expected world directions are worked out by hand from §7: relative words
	// map to the compass as if facing south, then the facing's rotation turns them.
	//            door faces   left-eave stairs face   lintel axis
	//   SOUTH    SOUTH        WEST                    X
	//   WEST     WEST         NORTH                   Z
	//   NORTH    NORTH        EAST                    X
	//   EAST     EAST         SOUTH                   Z
	@GameTest(structure = ARENA, maxTicks = 400)
	public void demoHouseFacingSouth(GameTestHelper helper) {
		demoHouse(helper, Facing.SOUTH, Direction.SOUTH, Direction.WEST, Direction.Axis.X);
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void demoHouseFacingWest(GameTestHelper helper) {
		demoHouse(helper, Facing.WEST, Direction.WEST, Direction.NORTH, Direction.Axis.Z);
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void demoHouseFacingNorth(GameTestHelper helper) {
		demoHouse(helper, Facing.NORTH, Direction.NORTH, Direction.EAST, Direction.Axis.X);
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void demoHouseFacingEast(GameTestHelper helper) {
		demoHouse(helper, Facing.EAST, Direction.EAST, Direction.SOUTH, Direction.Axis.Z);
	}

	private static void demoHouse(GameTestHelper helper, Facing facing, Direction door, Direction leftEave, Direction.Axis lintel) {
		ArenaBuild build = ArenaBuilds.start(helper, facing, ArenaBuilds.demoHouse(), Occupancy.players(helper.getLevel()),
				Tunables.BLOCKS_PER_TICK);
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			BlockState lower = build.state(0, 1, 2);
			helper.assertTrue(lower.is(Blocks.OAK_DOOR) && lower.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
					&& lower.getValue(DoorBlock.FACING) == door, "door's lower half: " + lower);
			BlockState upper = build.state(0, 2, 2);
			helper.assertTrue(upper.is(Blocks.OAK_DOOR) && upper.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER,
					"door's upper half: " + upper);
			BlockState eave = build.state(-5, 5, 6);
			helper.assertTrue(eave.is(Blocks.SPRUCE_STAIRS) && eave.getValue(StairBlock.FACING) == leftEave, "left eave: " + eave);
			BlockState log = build.state(0, 4, 2);
			helper.assertTrue(log.is(Blocks.OAK_LOG) && log.getValue(RotatedPillarBlock.AXIS) == lintel, "lintel: " + log);
			BlockState head = build.state(3, 1, 7);
			helper.assertTrue(head.is(Blocks.BED.pick(DyeColor.RED)) && head.getValue(AbstractBedBlock.PART) == BedPart.HEAD,
					"bed head: " + head);
			BlockState poppy = build.state(-3, 1, 0);
			helper.assertTrue(poppy.is(Blocks.POPPY), "the poppy on the grass is gone: " + poppy);
		});
	}

	/** Sand placed before the stone under it, in an earlier batch, is still there once the stone has arrived. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void sandWaitsForItsSupport(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "sand", "style": "test"}},
				  {"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 3, 4], "block": "sand"}]}},
				  {"tool": "checkpoint", "args": {"phase": "sand"}},
				  {"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 2, 4], "block": "stone"}, {"pos": [0, 1, 4], "block": "stone"}]}},
				  {"tool": "finish", "args": {"summary": "sand on stone"}}
				]}""");
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenIdle(10)
				.thenExecute(() -> helper.assertTrue(build.state(0, 3, 4).is(Blocks.SAND), "the sand fell: " + build.state(0, 3, 4)))
				.thenSucceed();
	}

	/** Water stays exactly as placed: the strict flags never start it flowing, and the finalize pass skips it. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void waterNeverFlows(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "water", "style": "test"}},
				  {"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "water"}]}},
				  {"tool": "finish", "args": {"summary": "a puddle"}}
				]}""");
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenIdle(40)
				.thenExecute(() -> {
					helper.assertTrue(build.state(0, 1, 4).is(Blocks.WATER), "the water is gone: " + build.state(0, 1, 4));
					for (Pos side : new Pos[] {new Pos(1, 1, 4), new Pos(-1, 1, 4), new Pos(0, 1, 5), new Pos(0, 1, 3)}) {
						BlockState state = build.level().getBlockState(build.world(side));
						helper.assertTrue(state.isAir(), "water flowed to " + side + ": " + state);
					}
				})
				.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void fencesConnectAfterFinalize(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "fence", "style": "test"}},
				  {"tool": "line", "args": {"from": [-1, 1, 4], "to": [1, 1, 4], "block": "oak_fence"}},
				  {"tool": "finish", "args": {"summary": "a fence"}}
				]}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			BlockState middle = build.state(0, 1, 4);
			helper.assertTrue(middle.is(Blocks.OAK_FENCE) && middle.getValue(CrossCollisionBlock.EAST)
					&& middle.getValue(CrossCollisionBlock.WEST), "the middle fence isn't connected: " + middle);
		});
	}

	/**
	 * The changed-blocks cap counts what a build really changes (spec §5.4): 2,400 blocks of air over air change
	 * nothing; then one batch of six stones meets a cap of 3, and the job itself drops the upper three, since the
	 * tools couldn't know when they queued them (M2 review).
	 */
	@GameTest(structure = ARENA, maxTicks = 600)
	public void capCountsRealChangesOnly(GameTestHelper helper) {
		ArenaBuild build = ArenaBuilds.start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "cap", "style": "test"}},
				  {"tool": "fill", "args": {"box": [-7, 1, 0, 7, 10, 15], "block": "air"}},
				  {"tool": "checkpoint", "args": {"phase": "air"}},
				  {"tool": "fill", "args": {"box": [-1, 1, 4, 1, 2, 4], "block": "stone"}},
				  {"tool": "finish", "args": {"summary": "three stones"}}
				]}""", Occupancy.players(helper.getLevel()), Tunables.BLOCKS_PER_TICK, 3);
		helper.succeedWhen(() -> {
			helper.assertTrue(build.done(), "the build hasn't finished");
			for (int x = -1; x <= 1; x++) {
				helper.assertTrue(build.state(x, 1, 4).is(Blocks.STONE), "under the cap but not placed: " + x);
				helper.assertTrue(build.state(x, 2, 4).isAir(), "placed over the cap: " + x);
			}
			helper.assertTrue(build.job().capReached(), "the job doesn't know it reached its cap");
			helper.assertValueEqual(build.journal().size(), 3, "positions the build changed");
		});
	}

	/**
	 * A spectator has no body to trap, so a block goes in where one floats (M2 review). The helper's in-level mock
	 * player always reports creative, so this checks the rule on mocks made in each game mode.
	 */
	@GameTest
	public void spectatorsDontHoldBackBlocks(GameTestHelper helper) {
		helper.assertFalse(Occupancy.inTheWay(helper.makeMockServerPlayer(GameType.SPECTATOR)), "a spectator holds back blocks");
		helper.assertTrue(Occupancy.inTheWay(helper.makeMockServerPlayer(GameType.SURVIVAL)), "a player in survival doesn't hold back blocks");
		helper.succeed();
	}

	/**
	 * A position a player stands in waits, retried every tick, while the rest of the build goes on around it. The
	 * "player" leaves only once the test has seen the rest placed: the test server ticks faster than real time, so
	 * leaving at a fixed tick would race the script's thread.
	 */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void blocksWaitForAPlayerToMove(GameTestHelper helper) {
		BlockPos standing = ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(0, 1, 4));
		AtomicBoolean stillThere = new AtomicBoolean(true);
		ArenaBuild build = ArenaBuilds.start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "line", "style": "test"}},
				  {"tool": "line", "args": {"from": [-1, 1, 4], "to": [1, 1, 4], "block": "stone"}},
				  {"tool": "checkpoint", "args": {"phase": "line"}},
				  {"tool": "finish", "args": {"summary": "a line"}}
				]}""", pos -> pos.equals(standing) && stillThere.get(), Tunables.BLOCKS_PER_TICK);
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(build.state(-1, 1, 4).is(Blocks.STONE) && build.state(1, 1, 4).is(Blocks.STONE),
						"the free positions weren't placed"))
				.thenIdle(5)
				.thenExecute(() -> {
					helper.assertTrue(build.state(0, 1, 4).isAir(), "placed a block where a player stands: " + build.state(0, 1, 4));
					helper.assertFalse(build.done(), "the build finished while a position was waiting");
					stillThere.set(false);
				})
				.thenWaitUntil(() -> {
					ArenaBuilds.assertDone(helper, build);
					helper.assertTrue(build.state(0, 1, 4).is(Blocks.STONE), "the waiting position was never placed: " + build.state(0, 1, 4));
				})
				.thenSucceed();
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void twoBlockBlocksGetTheirOtherHalf(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "halves", "style": "test"}},
				  {"tool": "set_blocks", "args": {"blocks": [
				    {"pos": [-2, 1, 4], "block": "oak_door", "state": {"half": "lower"}},
				    {"pos": [0, 1, 4], "block": "red_bed", "state": {"facing": "forward"}},
				    {"pos": [2, 0, 4], "block": "grass_block"},
				    {"pos": [2, 1, 4], "block": "tall_grass"}
				  ]}},
				  {"tool": "finish", "args": {"summary": "halves"}}
				]}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			BlockState door = build.state(-2, 2, 4);
			helper.assertTrue(door.is(Blocks.OAK_DOOR) && door.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER, "door's upper half: " + door);
			BlockState head = build.state(0, 1, 5);
			helper.assertTrue(head.is(Blocks.BED.pick(DyeColor.RED)) && head.getValue(AbstractBedBlock.PART) == BedPart.HEAD,
					"bed head: " + head);
			BlockState grass = build.state(2, 2, 4);
			helper.assertTrue(grass.is(Blocks.TALL_GRASS) && grass.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER,
					"tall grass's upper half: " + grass);
		});
	}

	/** A block placed where the build already placed one counts once, so "Done: N placed" matches "Undone: N restored". */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void overwritingABlockCountsItOnce(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "overwrite", "style": "test"}},
				  {"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "stone"}]}},
				  {"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "oak_planks"}]}},
				  {"tool": "finish", "args": {"summary": "one block"}}
				]}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			helper.assertTrue(build.state(0, 1, 4).is(Blocks.OAK_PLANKS), "the second block didn't replace the first");
			helper.assertValueEqual(build.job().placed(), 1, "blocks placed");
		});
	}

	/** Leaves are placed the way a player places them, so a hedge never decays. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void leavesStayPersistent(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "hedge", "style": "test"}},
				  {"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "oak_leaves"}]}},
				  {"tool": "finish", "args": {"summary": "a hedge"}}
				]}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			BlockState leaves = build.state(0, 1, 4);
			helper.assertTrue(leaves.is(Blocks.OAK_LEAVES) && leaves.getValue(LeavesBlock.PERSISTENT), "leaves: " + leaves);
		});
	}

	private static ArenaBuild start(GameTestHelper helper, String script) {
		return ArenaBuilds.start(helper, Facing.SOUTH, script, Occupancy.players(helper.getLevel()), Tunables.BLOCKS_PER_TICK);
	}
}
