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
import io.palmisano.aibuilder.world.BlockEntityHash;
import io.palmisano.aibuilder.world.McWorldReader;
import io.palmisano.aibuilder.world.Occupancy;
import io.palmisano.aibuilder.world.Positions;
import io.palmisano.aibuilder.world.ProtectionHeuristic;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
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

	private static Column readColumn(GameTestHelper helper, CompletableFuture<TerrainSnapshot> read, int x, int z) {
		helper.assertTrue(read.isDone(), "the survey hasn't come back");
		return read.join().at(x, z);
	}

	/**
	 * A mountain is read to its top (here the allowed box's, y 11), not as a plateau at 0 with a "ceiling" over it:
	 * the origin's rule climbs only 16 (M3 review).
	 */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void surveyReadsAMountain(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int y = 1; y <= 22; y++) {
			level.setBlock(ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(0, y, 4)), Blocks.STONE.defaultBlockState(), SIDE_EFFECT_FREE);
		}
		McWorldReader world = reader(helper, Facing.SOUTH, List.of());
		CompletableFuture<TerrainSnapshot> read = offThread(() -> world.terrain(0, 4, 1, 1));
		helper.succeedWhen(() -> {
			Column peak = readColumn(helper, read, 0, 4);
			helper.assertTrue(peak.floor() == 11 && peak.ceiling() == Column.NONE, "the mountain: " + peak);
		});
	}

	/** A pond on raised ground reads to its surface, with its depth (M3 review). */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void surveyReadsARaisedPond(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		level.setBlock(ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(0, 1, 4)), Blocks.STONE.defaultBlockState(), SIDE_EFFECT_FREE);
		for (int y = 2; y <= 3; y++) {
			level.setBlock(ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(0, y, 4)), Blocks.WATER.defaultBlockState(), SIDE_EFFECT_FREE);
		}
		McWorldReader world = reader(helper, Facing.SOUTH, List.of());
		CompletableFuture<TerrainSnapshot> read = offThread(() -> world.terrain(0, 4, 1, 1));
		helper.succeedWhen(() -> {
			Column pond = readColumn(helper, read, 0, 4);
			helper.assertTrue(pond.floor() == 3 && pond.water() == 2 && pond.floorBlock().equals("minecraft:water"), "the pond: " + pond);
		});
	}

	/** Lily pads, bamboo and cactus are plants, not the floor (spec §5.4), even though they have a collision shape. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void plantsAreNotTheFloor(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		// A pond level with the arena floor, under a lily pad.
		level.setBlock(ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(0, 0, 4)), Blocks.WATER.defaultBlockState(), SIDE_EFFECT_FREE);
		level.setBlock(ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(0, 1, 4)), Blocks.LILY_PAD.defaultBlockState(), SIDE_EFFECT_FREE);
		for (int y = 1; y <= 6; y++) {
			level.setBlock(ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(1, y, 4)), Blocks.BAMBOO.defaultBlockState(), SIDE_EFFECT_FREE);
		}
		for (int y = 1; y <= 3; y++) {
			level.setBlock(ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(2, y, 4)), Blocks.CACTUS.defaultBlockState(), SIDE_EFFECT_FREE);
		}
		McWorldReader world = reader(helper, Facing.SOUTH, List.of());
		CompletableFuture<TerrainSnapshot> read = offThread(() -> world.terrain(0, 4, 3, 1));
		helper.succeedWhen(() -> {
			Column pond = readColumn(helper, read, 0, 4);
			helper.assertTrue(pond.floor() == 0 && pond.floorBlock().equals("minecraft:water"), "the lily pad pond: " + pond);
			helper.assertTrue(readColumn(helper, read, 1, 4).floor() == 0, "bamboo: " + readColumn(helper, read, 1, 4));
			helper.assertTrue(readColumn(helper, read, 2, 4).floor() == 0, "cactus: " + readColumn(helper, read, 2, 4));
		});
	}

	/** A survey wider than the site still shows what's protected in the allowed box: writes there are refused (M3 review). */
	@GameTest(structure = ARENA, maxTicks = 600)
	public void aWiderSurveyStillShowsProtection(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		level.setBlock(ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(0, 1, 4)), Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_ALL);
		McWorldReader world = reader(helper, Facing.SOUTH, List.of());
		// The Small site is 41×41 around the box; this is wider, so it's a survey of its own.
		CompletableFuture<TerrainSnapshot> read = offThread(() -> world.terrain(-30, -10, 61, 61));
		helper.succeedWhen(() -> {
			Column planks = readColumn(helper, read, 0, 4);
			helper.assertTrue(planks.protectedCount() == 1 && planks.protectedBlock().equals("minecraft:oak_planks"), "the planks: " + planks);
		});
	}

	/** Two terraforms over the same ground: the second works from what the first left (M3 review). */
	@GameTest(structure = ARENA, maxTicks = 600)
	public void terraformTwiceOverTheSameGround(GameTestHelper helper) {
		ArenaBuild build = ArenaBuilds.start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "plinth", "style": "test"}},
				  {"tool": "terraform", "args": {"box": [-2, 0, 3, 2, 0, 5], "target_y": 3, "surface_block": "oak_planks", "fill_block": "stone"}},
				  {"tool": "terraform", "args": {"box": [-2, 0, 3, 2, 0, 5], "target_y": 1, "surface_block": "cobblestone", "fill_block": "stone"}},
				  {"tool": "finish", "args": {"summary": "a low plinth"}}
				]}""", Occupancy.players(helper.getLevel()), Tunables.BLOCKS_PER_TICK);
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			helper.assertTrue(build.state(0, 1, 4).is(Blocks.COBBLESTONE), "the surface: " + build.state(0, 1, 4));
			helper.assertTrue(build.state(0, 2, 4).isAir() && build.state(0, 3, 4).isAir(),
					"the first terraform's plinth is still there: " + build.state(0, 2, 4) + ", " + build.state(0, 3, 4));
		});
	}

	/** Natural blocks the vanilla tags miss count as natural; player-made blocks don't (M3 review). */
	@GameTest
	public void naturalBlocksAreNotProtected(GameTestHelper helper) {
		for (Block natural : List.of(Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.PUMPKIN, Blocks.MELON, Blocks.COCOA, Blocks.BEE_NEST,
				Blocks.CRIMSON_FUNGUS, Blocks.WARPED_FUNGUS, Blocks.INFESTED_STONE, Blocks.INFESTED_DEEPSLATE, Blocks.SUSPICIOUS_GRAVEL,
				Blocks.SUSPICIOUS_SAND, Blocks.BAMBOO_SAPLING, Blocks.SCULK_SENSOR, Blocks.SCULK_SHRIEKER, Blocks.SCULK_CATALYST, Blocks.TURTLE_EGG,
				Blocks.FROGSPAWN, Blocks.CREAKING_HEART, Blocks.RESIN_CLUMP)) {
			helper.assertTrue(ProtectionHeuristic.isNatural(natural.defaultBlockState()), natural + " counts as player-made");
		}
		for (Block made : List.of(Blocks.OAK_PLANKS, Blocks.GLASS, Blocks.STONE_BRICKS, Blocks.TORCH, Blocks.CHEST, Blocks.CRAFTING_TABLE,
				Blocks.BOOKSHELF, Blocks.COBBLESTONE)) {
			helper.assertFalse(ProtectionHeuristic.isNatural(made.defaultBlockState()), made + " counts as natural");
		}
		helper.succeed();
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
		// A chest the earlier build placed empty, that the player has filled since: theirs now (M3 review).
		Pos chestLocal = new Pos(4, 1, 4);
		BlockPos chest = ArenaBuilds.worldPos(helper, Facing.SOUTH, chestLocal);
		level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
		earlier.beforeWrite(Positions.toPos(chest), Blocks.AIR.defaultBlockState(), null);
		earlier.afterWrite(Positions.toPos(chest), "minecraft:chest", BlockEntityHash.of(level.getBlockEntity(chest), level.registryAccess()));
		((ChestBlockEntity) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 3));
		McWorldReader world = reader(helper, Facing.SOUTH, List.of(earlier));
		CompletableFuture<ProtectionMask> read = offThread(world::protection);
		helper.succeedWhen(() -> {
			helper.assertTrue(read.isDone(), "the protection mask hasn't come back");
			helper.assertFalse(read.join().isProtected(ownLocal), "the player's earlier AI build is protected from them");
			helper.assertTrue(read.join().isProtected(otherLocal), "other player-made planks aren't protected");
			helper.assertTrue(read.join().isProtected(chestLocal), "the chest the player filled isn't protected");
		});
	}

	/** Lava never goes in beside wood the world already has: lava lights fires even when it doesn't flow (spec §9). */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void lavaBesideTheWorldsWoodIsRejected(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		level.setBlock(ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(2, 1, 4)), Blocks.OAK_PLANKS.defaultBlockState(), SIDE_EFFECT_FREE);
		ArenaBuild build = ArenaBuilds.start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "lava pool", "style": "test"}},
				  {"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "lava"}]}},
				  {"tool": "finish", "args": {"summary": "a pool"}}
				]}""", Occupancy.players(level), Tunables.BLOCKS_PER_TICK);
		helper.succeedWhen(() -> {
			helper.assertTrue(build.done(), "the build hasn't finished");
			helper.assertTrue(build.errors().stream().anyMatch(e -> e.contains("#0 lava_near_flammable")), "the lava went in: " + build.errors());
			helper.assertTrue(build.state(0, 1, 4).isAir(), "lava beside the planks: " + build.state(0, 1, 4));
		});
	}

	/** Nor wood beside the world's lava; farther off it goes in. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void woodBesideTheWorldsLavaIsRejected(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		level.setBlock(ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(0, 1, 4)), Blocks.LAVA.defaultBlockState(), SIDE_EFFECT_FREE);
		ArenaBuild build = ArenaBuilds.start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "bridge", "style": "test"}},
				  {"tool": "set_blocks", "args": {"blocks": [{"pos": [1, 3, 5], "block": "oak_planks"}]}},
				  {"tool": "set_blocks", "args": {"blocks": [{"pos": [4, 1, 4], "block": "oak_planks"}]}},
				  {"tool": "finish", "args": {"summary": "a bridge"}}
				]}""", Occupancy.players(level), Tunables.BLOCKS_PER_TICK);
		helper.succeedWhen(() -> {
			helper.assertTrue(build.done(), "the build hasn't finished");
			helper.assertTrue(build.errors().size() == 1 && build.errors().getFirst().contains("#0 lava_near_flammable"),
					"the planks above the lava went in: " + build.errors());
			helper.assertTrue(build.state(1, 3, 5).isAir(), "planks above the lava: " + build.state(1, 3, 5));
			helper.assertTrue(build.state(4, 1, 4).is(Blocks.OAK_PLANKS), "the planks 4 away: " + build.state(4, 1, 4));
		});
	}
}
