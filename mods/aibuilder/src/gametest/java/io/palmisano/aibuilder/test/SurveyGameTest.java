package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.ArenaBuilds.ARENA;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.ProtectionMask;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.survey.Column;
import io.palmisano.aibuilder.core.survey.TerrainSnapshot;
import io.palmisano.aibuilder.test.ArenaBuilds.ArenaBuild;
import io.palmisano.aibuilder.world.McWorldReader;
import io.palmisano.aibuilder.world.Occupancy;
import io.palmisano.aibuilder.world.Positions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** The survey, protection and region reads, from a real world (spec §11). */
public class SurveyGameTest {
	private static final int SIDE_EFFECT_FREE = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

	/** The reads wait on the server thread, so a test asks for them from another thread and waits on a condition. */
	private static <T> CompletableFuture<T> offThread(java.util.function.Supplier<T> read) {
		return CompletableFuture.supplyAsync(read);
	}

	private static McWorldReader reader(GameTestHelper helper, Facing facing, List<UndoJournal<BlockState, CompoundTag>> earlier) {
		LocalFrame frame = ArenaBuilds.frame(helper, facing);
		return new McWorldReader(helper.getLevel(), frame, ArenaBuilds.ALLOWED, SizeTier.SMALL, () -> null, earlier);
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void surveyReadsTheArena(GameTestHelper helper) {
		McWorldReader world = reader(helper, Facing.SOUTH, List.of());
		CompletableFuture<TerrainSnapshot> read = offThread(() -> world.terrain(-3, 1, 7, 7));
		helper.succeedWhen(() -> {
			helper.assertTrue(read.isDone(), "the survey hasn't come back");
			for (Column column : read.join().columns()) {
				helper.assertTrue(column.floor() == 0 && column.floorBlock().equals("minecraft:stone") && column.water() == 0 && !column.tree(),
						"not the arena's floor: " + column);
			}
		});
	}

	/** Water and lava are surfaces, with their depth. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void surveyOverWaterAndLava(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int y = 1; y <= 2; y++) {
			level.setBlock(ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(0, y, 4)), Blocks.WATER.defaultBlockState(), SIDE_EFFECT_FREE);
		}
		level.setBlock(ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(1, 1, 4)), Blocks.LAVA.defaultBlockState(), SIDE_EFFECT_FREE);
		McWorldReader world = reader(helper, Facing.SOUTH, List.of());
		CompletableFuture<TerrainSnapshot> read = offThread(() -> world.terrain(0, 4, 2, 1));
		helper.succeedWhen(() -> {
			helper.assertTrue(read.isDone(), "the survey hasn't come back");
			Column water = read.join().at(0, 4);
			Column lava = read.join().at(1, 4);
			helper.assertTrue(water.floor() == 2 && water.water() == 2 && water.floorBlock().equals("minecraft:water"), "water: " + water);
			helper.assertTrue(lava.floor() == 1 && lava.lava() == 1, "lava: " + lava);
		});
	}

	/** Planks the player put down inside the box stay when the build fills over them; the stone floor doesn't. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void aPlayersBlocksSurviveAFill(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos planks = ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(0, 1, 4));
		level.setBlock(planks, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
		ArenaBuild build = ArenaBuilds.start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "slab", "style": "test"}},
				  {"tool": "fill", "args": {"box": [-1, 0, 3, 1, 1, 5], "block": "cobblestone"}},
				  {"tool": "finish", "args": {"summary": "a slab"}}
				]}""", Occupancy.players(level), Tunables.BLOCKS_PER_TICK);
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			helper.assertTrue(level.getBlockState(planks).is(Blocks.OAK_PLANKS), "the player's planks were built over");
			helper.assertTrue(build.state(1, 1, 4).is(Blocks.COBBLESTONE) && build.state(0, 0, 4).is(Blocks.COBBLESTONE),
					"the rest of the fill didn't go in");
		});
	}

	/** inspect_region reads the world as it is, directions relative to the build's facing. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void inspectRegionSeesWhatTheBuildPlaced(GameTestHelper helper) {
		ArenaBuild build = ArenaBuilds.start(helper, Facing.WEST, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "step", "style": "test"}},
				  {"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "oak_stairs", "state": {"facing": "forward"}}]}},
				  {"tool": "finish", "args": {"summary": "a step"}}
				]}""", Occupancy.players(helper.getLevel()), Tunables.BLOCKS_PER_TICK);
		AtomicReference<CompletableFuture<List<String>>> read = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> {
					McWorldReader world = reader(helper, Facing.WEST, List.of());
					read.set(offThread(() -> world.states(Box.of(0, 1, 4, 0, 1, 4))));
				})
				.thenWaitUntil(() -> helper.assertTrue(read.get().isDone(), "the region hasn't come back"))
				.thenExecute(() -> helper.assertValueEqual(read.get().join(),
						List.of("minecraft:oak_stairs[facing=forward,half=bottom,shape=straight,waterlogged=false]"), "states"))
				.thenSucceed();
	}

	/** What the same player's earlier builds left is theirs to change; other player-made blocks are protected. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void earlierBuildsAreNotProtected(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		Pos ownLocal = new Pos(0, 1, 4);
		Pos otherLocal = new Pos(2, 1, 4);
		BlockPos own = ArenaBuilds.worldPos(helper, Facing.SOUTH, ownLocal);
		level.setBlock(own, Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
		level.setBlock(ArenaBuilds.worldPos(helper, Facing.SOUTH, otherLocal), Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
		UndoJournal<BlockState, CompoundTag> earlier = new UndoJournal<>();
		earlier.beforeWrite(Positions.toPos(own), Blocks.AIR.defaultBlockState(), null);
		earlier.afterWrite(Positions.toPos(own), "minecraft:oak_planks", 0);
		McWorldReader world = reader(helper, Facing.SOUTH, List.of(earlier));
		CompletableFuture<ProtectionMask> read = offThread(world::protection);
		helper.succeedWhen(() -> {
			helper.assertTrue(read.isDone(), "the protection mask hasn't come back");
			helper.assertFalse(read.join().isProtected(ownLocal), "the player's earlier AI build is protected from them");
			helper.assertTrue(read.join().isProtected(otherLocal), "other player-made planks aren't protected");
		});
	}
}
