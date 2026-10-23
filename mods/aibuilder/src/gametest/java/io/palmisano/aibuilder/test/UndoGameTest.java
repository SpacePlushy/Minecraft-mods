package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.ArenaBuilds.ARENA;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.blocks.Validation;
import io.palmisano.aibuilder.core.blocks.Validator;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.test.ArenaBuilds.ArenaBuild;
import io.palmisano.aibuilder.world.JobScheduler;
import io.palmisano.aibuilder.world.McBlockCatalog;
import io.palmisano.aibuilder.world.Occupancy;
import io.palmisano.aibuilder.world.Positions;
import io.palmisano.aibuilder.world.UndoRunner;
import io.palmisano.aibuilder.world.UndoStore;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;

/** Undo in a real world: exact restores with contents, toggles, the player's own changes, cancels, far chunks, saving. */
public class UndoGameTest {
	@GameTest(structure = ARENA, maxTicks = 400)
	public void undoRestoresEverythingIncludingContents(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos chestPos = ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(0, 1, 4));
		BlockPos signPos = ArenaBuilds.worldPos(helper, Facing.SOUTH, new Pos(1, 1, 4));
		level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
		((ChestBlockEntity) level.getBlockEntity(chestPos)).setItem(0, new ItemStack(Items.DIAMOND, 3));
		level.setBlock(signPos, Blocks.OAK_SIGN.defaultBlockState(), Block.UPDATE_ALL);
		SignBlockEntity sign = (SignBlockEntity) level.getBlockEntity(signPos);
		sign.setText(sign.getText(SignTextSlot.FRONT).asMutable().setLine(0, Component.literal("hello")).asImmutable(), SignTextSlot.FRONT);
		ArenaBuild build = start(helper, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "cover", "style": "test"}},
				  {"tool": "clear", "args": {"box": [-1, 1, 3, 1, 2, 5], "include_protected": true}},
				  {"tool": "fill", "args": {"box": [-1, 1, 3, 1, 2, 5], "block": "stone"}},
				  {"tool": "finish", "args": {"summary": "a stone block"}}
				]}""");
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> {
					helper.assertTrue(level.getBlockState(chestPos).is(Blocks.STONE), "the build didn't cover the chest");
					undo.set(undo(level, build.journal()));
				})
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> {
					helper.assertTrue(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest && chest.getItem(0).is(Items.DIAMOND)
							&& chest.getItem(0).getCount() == 3, "the chest lost its diamonds");
					helper.assertTrue(level.getBlockEntity(signPos) instanceof SignBlockEntity restored
							&& restored.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString().equals("hello"),
							"the sign lost its text");
					for (Pos pos : build.journal().positions()) {
						BlockState now = level.getBlockState(Positions.toBlockPos(pos));
						helper.assertTrue(now == build.journal().original(pos), "not restored at " + pos + ": " + now);
					}
					helper.assertValueEqual(undo.get().result().join(), new UndoRunner.UndoResult(build.journal().size(), 0), "undo result");
				})
				.thenSucceed();
	}

	/** Undo takes back the build's own blocks whatever the player toggled on them since (spec §10). */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void togglesStillUndo(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ArenaBuild build = start(helper, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "door and lever", "style": "test"}},
				  {"tool": "set_blocks", "args": {"blocks": [
				    {"pos": [0, 1, 4], "block": "oak_door", "state": {"half": "lower"}},
				    {"pos": [2, 1, 4], "block": "lever", "state": {"face": "floor"}}
				  ]}},
				  {"tool": "finish", "args": {"summary": "a door and a lever"}}
				]}""");
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> {
					for (Pos half : List.of(new Pos(0, 1, 4), new Pos(0, 2, 4))) {
						BlockPos pos = build.world(half);
						level.setBlock(pos, level.getBlockState(pos).setValue(DoorBlock.OPEN, true), Block.UPDATE_ALL);
					}
					BlockPos lever = build.world(new Pos(2, 1, 4));
					level.setBlock(lever, level.getBlockState(lever).setValue(LeverBlock.POWERED, true), Block.UPDATE_ALL);
					helper.assertTrue(build.state(0, 1, 4).getValue(DoorBlock.OPEN) && build.state(2, 1, 4).getValue(LeverBlock.POWERED),
							"the door didn't open or the lever didn't switch");
					undo.set(undo(level, build.journal()));
				})
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> {
					for (Pos pos : List.of(new Pos(0, 1, 4), new Pos(0, 2, 4), new Pos(2, 1, 4))) {
						helper.assertTrue(build.state(pos.x(), pos.y(), pos.z()).isAir(), "not undone at " + pos + ": " + build.state(pos.x(), pos.y(), pos.z()));
					}
					helper.assertValueEqual(undo.get().result().join().kept(), 0, "kept");
				})
				.thenSucceed();
	}

	/** A chest the player filled after the build is theirs now: undo leaves it, and says so. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void aFilledChestIsKept(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ArenaBuild build = start(helper, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "chest", "style": "test"}},
				  {"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "chest"}]}},
				  {"tool": "finish", "args": {"summary": "a chest"}}
				]}""");
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		BlockPos chestPos = build.world(new Pos(0, 1, 4));
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> {
					((ChestBlockEntity) level.getBlockEntity(chestPos)).setItem(0, new ItemStack(Items.APPLE));
					undo.set(undo(level, build.journal()));
				})
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> {
					helper.assertTrue(level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest && chest.getItem(0).is(Items.APPLE),
							"the filled chest was undone: " + level.getBlockState(chestPos));
					helper.assertValueEqual(undo.get().result().join().kept(), 1, "kept");
				})
				.thenSucceed();
	}

	/** A cancelled build keeps its journal, so undo takes back exactly what it had placed. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void cancelMidBuildThenUndo(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ArenaBuild build = ArenaBuilds.start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "slab of stone", "style": "test"}},
				  {"tool": "fill", "args": {"box": [-4, 1, 2, 5, 2, 11], "block": "stone"}},
				  {"tool": "finish", "args": {"summary": "200 stone"}}
				]}""", Occupancy.players(level), 5);
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(build.job().placed() > 0, "nothing placed yet"))
				.thenExecute(build::cancel)
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> {
					int placed = build.job().placed();
					helper.assertTrue(placed > 0 && placed < 200, "placed " + placed + " of 200 before the cancel took");
					undo.set(undo(level, build.journal()));
				})
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> {
					for (Pos pos : build.journal().positions()) {
						BlockState now = level.getBlockState(Positions.toBlockPos(pos));
						helper.assertTrue(now.isAir(), "not undone at " + pos + ": " + now);
					}
				})
				.thenSucceed();
	}

	/** A spot already back to how it was (a crash cut an earlier undo short) counts as restored, not as the player's. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void aSpotAlreadyBackToItsOriginalCountsAsRestored(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ArenaBuild build = start(helper, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "two stones", "style": "test"}},
				  {"tool": "fill", "args": {"box": [0, 1, 4, 1, 1, 4], "block": "stone"}},
				  {"tool": "finish", "args": {"summary": "two stones"}}
				]}""");
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> {
					level.setBlock(build.world(new Pos(0, 1, 4)), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
					undo.set(undo(level, build.journal()));
				})
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> {
					helper.assertValueEqual(undo.get().result().join(), new UndoRunner.UndoResult(2, 0), "undo result");
					helper.assertTrue(build.state(1, 1, 4).isAir(), "not undone at (1, 1, 4): " + build.state(1, 1, 4));
				})
				.thenSucceed();
	}

	/**
	 * One block of every block-entity type the tools accept, left to tick for a few seconds, then undone: every one
	 * must still count as the build's own. (A hopper's data changes on its first tick.)
	 */
	@GameTest(structure = ARENA, maxTicks = 600)
	public void everyKindOfBlockEntityUndoesAfterItHasSat(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		List<String> ids = oneBlockPerBlockEntityType();
		JsonArray blocks = new JsonArray();
		// Two apart in a grid inside the arena, so nothing connects, and a bed's head (one forward) stays inside.
		for (int i = 0; i < ids.size() && i < 56; i++) {
			JsonObject entry = new JsonObject();
			JsonArray pos = new JsonArray();
			pos.add(-7 + 2 * (i % 8));
			pos.add(1);
			pos.add(1 + 2 * (i / 8));
			entry.add("pos", pos);
			entry.addProperty("block", ids.get(i));
			blocks.add(entry);
		}
		helper.assertTrue(ids.size() <= 56, ids.size() + " kinds of block entity don't fit the grid");
		ArenaBuild build = start(helper, "{\"calls\": [{\"tool\": \"submit_brief\", \"args\": {\"title\": \"block entities\", \"style\": \"test\"}},"
				+ "{\"tool\": \"set_blocks\", \"args\": {\"blocks\": " + blocks + "}},"
				+ "{\"tool\": \"finish\", \"args\": {\"summary\": \"one of each\"}}]}");
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenIdle(60)
				.thenExecute(() -> undo.set(undo(level, build.journal())))
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> {
					List<String> left = new ArrayList<>();
					for (Pos pos : build.journal().positions()) {
						BlockState now = level.getBlockState(Positions.toBlockPos(pos));
						if (now != build.journal().original(pos)) {
							left.add(BuiltInRegistries.BLOCK.getKey(now.getBlock()) + " at " + pos);
						}
					}
					helper.assertTrue(left.isEmpty(), "undo left " + left);
					helper.assertValueEqual(undo.get().result().join().kept(), 0, "kept");
				})
				.thenSucceed();
	}

	/** The first block the tools accept for each kind of block entity. */
	private static List<String> oneBlockPerBlockEntityType() {
		Validator validator = new Validator(McBlockCatalog.get(), Validator.DEFAULT_BANNED);
		Map<BlockEntityType<?>, String> oneEach = new LinkedHashMap<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			String id = BuiltInRegistries.BLOCK.getKey(block).toString();
			if (!(block instanceof EntityBlock entityBlock) || !(validator.validate(BlockSpec.of(id)) instanceof Validation.Valid)) {
				continue;
			}
			BlockEntity entity = entityBlock.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
			if (entity != null) {
				oneEach.putIfAbsent(entity.getType(), id);
			}
		}
		return List.copyOf(oneEach.values());
	}

	/**
	 * Undo far from anyone: the runner loads the chunks itself. The positions are 200 blocks east of the arena and
	 * 40 up, clear of every other test's structure.
	 */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void undoReachesFarPositions(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos base = helper.absolutePos(new BlockPos(8, 0, 8)).offset(200, 40, 0);
		List<BlockPos> far = List.of(base, base.east(), base.east(2));
		UndoJournal<BlockState, CompoundTag> journal = new UndoJournal<>();
		for (BlockPos pos : far) {
			level.setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
			journal.beforeWrite(Positions.toPos(pos), Blocks.AIR.defaultBlockState(), null);
			journal.afterWrite(Positions.toPos(pos), "minecraft:stone", 0);
		}
		UndoRunner undo = undo(level, journal);
		helper.succeedWhen(() -> {
			helper.assertTrue(undo.result().isDone(), "the undo hasn't finished");
			helper.assertValueEqual(undo.result().join().restored(), 3, "restored");
			for (BlockPos pos : far) {
				helper.assertTrue(level.getBlockState(pos).isAir(), "still stone at " + pos);
			}
		});
	}

	/** What /aibuild undo reads back after the world is saved and loaded: everything, builds in their order. */
	@GameTest
	public void journalSurvivesSaving(GameTestHelper helper) {
		UndoJournal<BlockState, CompoundTag> first = new UndoJournal<>();
		CompoundTag chestData = new CompoundTag();
		chestData.putString("id", "minecraft:chest");
		chestData.putString("CustomName", "\"loot\"");
		first.beforeWrite(new Pos(1, 64, 2), Blocks.CHEST.defaultBlockState(), chestData);
		first.afterWrite(new Pos(1, 64, 2), "minecraft:stone", 0);
		first.beforeWrite(new Pos(-3, -10, 7), Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST), null);
		first.afterWrite(new Pos(-3, -10, 7), "minecraft:oak_planks", 12345);
		first.beforeWrite(new Pos(0, 300, 0), Blocks.AIR.defaultBlockState(), null);
		first.afterWrite(new Pos(0, 300, 0), "minecraft:chest", -42);
		UndoJournal<BlockState, CompoundTag> second = new UndoJournal<>();
		second.beforeWrite(new Pos(5, 5, 5), Blocks.AIR.defaultBlockState(), null);
		second.afterWrite(new Pos(5, 5, 5), "minecraft:glass", 0);

		UUID player = UUID.randomUUID();
		UndoStore store = new UndoStore();
		store.push(player, new UndoStore.StoredBuild(Level.NETHER, "a tower", first));
		store.push(player, new UndoStore.StoredBuild(Level.OVERWORLD, "a window", second));
		Tag saved = UndoStore.CODEC.encodeStart(NbtOps.INSTANCE, store).getOrThrow();
		UndoStore loaded = UndoStore.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();

		UndoStore.StoredBuild newest = loaded.pop(player).orElseThrow();
		helper.assertValueEqual(newest.title(), "a window", "newest build");
		helper.assertValueEqual(newest.journal().entries(), second.entries(), "newest journal");
		UndoStore.StoredBuild older = loaded.pop(player).orElseThrow();
		helper.assertValueEqual(older.dimension(), Level.NETHER, "dimension");
		helper.assertValueEqual(older.title(), "a tower", "title");
		helper.assertValueEqual(older.journal().entries(), first.entries(), "journal");
		helper.assertTrue(loaded.pop(player).isEmpty(), "an extra build came back");
		helper.succeed();
	}

	/** A saved build naming a block the game no longer has (a mod's, removed since) costs only that build. */
	@GameTest
	public void aBuildThatCantBeReadIsSkipped(GameTestHelper helper) {
		UndoJournal<BlockState, CompoundTag> window = new UndoJournal<>();
		window.beforeWrite(new Pos(5, 5, 5), Blocks.AIR.defaultBlockState(), null);
		window.afterWrite(new Pos(5, 5, 5), "minecraft:glass", 0);
		UndoJournal<BlockState, CompoundTag> pit = new UndoJournal<>();
		pit.beforeWrite(new Pos(1, 64, 2), Blocks.SPONGE.defaultBlockState(), null);
		pit.afterWrite(new Pos(1, 64, 2), "minecraft:stone", 0);
		UUID player = UUID.randomUUID();
		UndoStore store = new UndoStore();
		store.push(player, new UndoStore.StoredBuild(Level.OVERWORLD, "a window", window));
		store.push(player, new UndoStore.StoredBuild(Level.OVERWORLD, "a pit", pit));
		String saved = UndoStore.CODEC.encodeStart(NbtOps.INSTANCE, store).getOrThrow().toString();
		helper.assertTrue(saved.contains("\"minecraft:sponge\""), "no sponge in " + saved);
		CompoundTag broken;
		try {
			// As if the sponge were a block of a mod removed since.
			broken = TagParser.parseCompoundFully(saved.replace("\"minecraft:sponge\"", "\"gone:sponge\""));
		} catch (CommandSyntaxException e) {
			throw new IllegalStateException(e);
		}
		// As the game reads saved data: the result, or what it could read of it.
		UndoStore loaded = UndoStore.CODEC.parse(NbtOps.INSTANCE, broken).resultOrPartial(error -> { }).orElseThrow();
		helper.assertValueEqual(loaded.builds(player).stream().map(UndoStore.StoredBuild::title).toList(), List.of("a window"),
				"the builds that load");
		helper.assertValueEqual(loaded.peek(player).orElseThrow().journal().entries(), window.entries(), "the window's journal");
		helper.succeed();
	}

	/** An undo history saved before moving parts, whose entities have no kind, reads them as the item frames they are. */
	@GameTest
	public void anOldSaveStillReads(GameTestHelper helper) {
		CompoundTag old;
		try {
			old = TagParser.parseCompoundFully("""
					{players: [{player: [I; 1, 2, 3, 4], builds: [{dimension: "minecraft:overworld", title: "a frame", journal: {
					  positions: [L;], states: [], state_index: [I;], written: [], written_index: [I;], hashes: [I;], data: [],
					  entities: [{id: [I; 5, 6, 7, 8], pos: 0L, item: "minecraft:apple"}]}}]}]}""");
		} catch (CommandSyntaxException e) {
			throw new IllegalStateException(e);
		}
		UndoStore loaded = UndoStore.CODEC.parse(NbtOps.INSTANCE, old).getOrThrow();
		List<UndoJournal.Spawned> spawned = loaded.peek(UUIDUtil.uuidFromIntArray(new int[] {1, 2, 3, 4})).orElseThrow().journal().spawned();
		helper.assertValueEqual(spawned, List.of(new UndoJournal.Spawned(UUIDUtil.uuidFromIntArray(new int[] {5, 6, 7, 8}), new Pos(0, 0, 0),
				"minecraft:apple", UndoJournal.Spawned.FRAME)), "what the old save spawned");
		helper.succeed();
	}

	private static ArenaBuild start(GameTestHelper helper, String script) {
		return ArenaBuilds.start(helper, Facing.SOUTH, script, Occupancy.players(helper.getLevel()), Tunables.BLOCKS_PER_TICK);
	}

	/** Starts undoing a journal, as /aibuild undo will. */
	private static UndoRunner undo(ServerLevel level, UndoJournal<BlockState, CompoundTag> journal) {
		UndoRunner runner = new UndoRunner(level, journal, Tunables.BLOCKS_PER_TICK);
		JobScheduler.add(runner);
		return runner;
	}
}
