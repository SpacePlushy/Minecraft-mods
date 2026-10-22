package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.ArenaBuilds.ARENA;

import io.palmisano.aibuilder.core.site.GroundFinder;
import io.palmisano.aibuilder.world.Sites;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Where a build stands, read from the real world: a fluid's surface is the floor, never what's under it. */
public class SiteGameTest {
	/** Facing a lava lake (the Nether's sea), the build stands on the lava, not out of sight at the bottom of it. */
	@GameTest(structure = ARENA)
	public void lavaIsASurface(GameTestHelper helper) {
		surfaceOf(helper, Blocks.LAVA.defaultBlockState());
	}

	@GameTest(structure = ARENA)
	public void waterIsASurface(GameTestHelper helper) {
		surfaceOf(helper, Blocks.WATER.defaultBlockState());
	}

	/** Two blocks of the fluid on the arena's floor, the player's feet two above: the top fluid block is the ground. */
	private static void surfaceOf(GameTestHelper helper, BlockState fluid) {
		ServerLevel level = helper.getLevel();
		BlockPos bottom = helper.absolutePos(new BlockPos(8, 1, 8));
		// Placed without side effects, so it stays put rather than spreading over the arena.
		level.setBlock(bottom, fluid, Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
		level.setBlock(bottom.above(), fluid, Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
		int ground = GroundFinder.find(Sites.column(level, bottom.getX(), bottom.getZ()), bottom.getY() + 4);
		helper.assertValueEqual(ground, bottom.getY() + 1, "ground y over " + fluid);
		helper.succeed();
	}
}
