package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.ArenaBuilds.ARENA;

import com.mojang.logging.LogUtils;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.test.ArenaBuilds.ArenaBuild;
import io.palmisano.aibuilder.world.Occupancy;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
}
