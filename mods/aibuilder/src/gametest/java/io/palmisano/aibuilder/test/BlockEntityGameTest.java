package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.ArenaBuilds.ARENA;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.test.ArenaBuilds.ArenaBuild;
import io.palmisano.aibuilder.world.JobScheduler;
import io.palmisano.aibuilder.world.Occupancy;
import io.palmisano.aibuilder.world.UndoRunner;
import io.palmisano.aibuilder.world.UndoStore;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** place_block_entity in a real world (spec §8, §10): what each block entity ends up holding, and item frames' undo. */
public class BlockEntityGameTest {
	private static ArenaBuild start(GameTestHelper helper, String calls) {
		return ArenaBuilds.start(helper, Facing.SOUTH, "{\"calls\": [{\"tool\": \"submit_brief\", \"args\": {\"title\": \"dressing\", \"style\": \"test\"}},"
				+ calls + ",{\"tool\": \"finish\", \"args\": {\"summary\": \"dressed\"}}]}", Occupancy.players(helper.getLevel()), Tunables.BLOCKS_PER_TICK);
	}

	private static String line(SignBlockEntity sign, SignTextSlot side, int line) {
		return sign.getText(side).getMessages(false).get(line).getString();
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void signReadsBack(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"tool": "place_block_entity", "args": {"pos": [0, 1, 4], "block": "oak_sign", "sign": {"front": ["Welcome", "to the", "Cottage"],
				  "back": ["Exit"], "color": "blue", "glowing": true}}}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			helper.assertTrue(build.level().getBlockEntity(build.world(new Pos(0, 1, 4))) instanceof SignBlockEntity sign
					&& line(sign, SignTextSlot.FRONT, 0).equals("Welcome") && line(sign, SignTextSlot.FRONT, 2).equals("Cottage")
					&& line(sign, SignTextSlot.BACK, 0).equals("Exit") && sign.getText(SignTextSlot.FRONT).getColor() == DyeColor.BLUE
					&& sign.getText(SignTextSlot.FRONT).hasGlowingText(), "the sign: " + build.level().getBlockEntity(build.world(new Pos(0, 1, 4))));
		});
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void chestHoldsItsItems(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"tool": "place_block_entity", "args": {"pos": [0, 1, 4], "block": "chest", "container": {"items": [{"item": "diamond", "count": 5},
				  {"item": "bread", "count": 64}, {"item": "iron_sword"}]}}}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			helper.assertTrue(build.level().getBlockEntity(build.world(new Pos(0, 1, 4))) instanceof ChestBlockEntity chest
					&& chest.getItem(0).is(Items.DIAMOND) && chest.getItem(0).getCount() == 5 && chest.getItem(1).is(Items.BREAD)
					&& chest.getItem(1).getCount() == 64 && chest.getItem(2).is(Items.IRON_SWORD) && chest.getItem(3).isEmpty(), "the chest's items");
		});
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void lecternHasTheBook(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"tool": "place_block_entity", "args": {"pos": [0, 1, 4], "block": "lectern", "lectern": {"title": "Guide", "author": "The Builder",
				  "pages": ["Welcome to the library.", "Page two"]}}}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			BlockPos pos = build.world(new Pos(0, 1, 4));
			helper.assertTrue(build.level().getBlockState(pos).getValue(LecternBlock.HAS_BOOK), "the lectern doesn't show a book");
			helper.assertTrue(build.level().getBlockEntity(pos) instanceof LecternBlockEntity lectern && lectern.hasBook(), "no book on the lectern");
			ItemStack book = ((LecternBlockEntity) build.level().getBlockEntity(pos)).getBook();
			WrittenBookContent content = book.get(DataComponents.WRITTEN_BOOK_CONTENT);
			helper.assertTrue(book.is(Items.WRITTEN_BOOK) && content != null && content.title().raw().equals("Guide")
					&& content.author().equals("The Builder") && content.pages().size() == 2
					&& content.pages().getFirst().raw().getString().equals("Welcome to the library."), "the book: " + content);
		});
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void bannerHasItsPatterns(GameTestHelper helper) {
		ArenaBuild build = start(helper, """
				{"tool": "place_block_entity", "args": {"pos": [0, 1, 4], "block": "white_banner", "banner": {"patterns": [
				  {"pattern": "stripe_bottom", "color": "red"}, {"pattern": "creeper", "color": "black"}]}}}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			helper.assertTrue(build.level().getBlockEntity(build.world(new Pos(0, 1, 4))) instanceof BannerBlockEntity banner, "no banner");
			List<BannerPatternLayers.Layer> layers = ((BannerBlockEntity) build.level().getBlockEntity(build.world(new Pos(0, 1, 4)))).getPatterns().layers();
			helper.assertTrue(layers.size() == 2 && layers.get(0).pattern().is(net.minecraft.resources.Identifier.withDefaultNamespace("stripe_bottom"))
					&& layers.get(0).color() == DyeColor.RED && layers.get(1).pattern().is(net.minecraft.resources.Identifier.withDefaultNamespace("creeper"))
					&& layers.get(1).color() == DyeColor.BLACK, "the banner's patterns: " + layers);
		});
	}

	/** A wall and a frame on its near face, holding a diamond, as a build places them. */
	private static ArenaBuild wallWithAFrame(GameTestHelper helper) {
		return start(helper, """
				{"tool": "fill", "args": {"box": [-1, 1, 5, 1, 2, 5], "block": "stone"}},
				{"tool": "place_block_entity", "args": {"pos": [0, 1, 4], "item_frame": {"facing": "back", "item": "diamond"}}}""");
	}

	private static List<ItemFrame> frames(ServerLevel level, BlockPos around) {
		return level.getEntitiesOfClass(ItemFrame.class, new AABB(around).inflate(3));
	}

	@GameTest(structure = ARENA, maxTicks = 400)
	public void itemFrameHoldsItsItem(GameTestHelper helper) {
		ArenaBuild build = wallWithAFrame(helper);
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			BlockPos at = build.world(new Pos(0, 1, 4));
			List<ItemFrame> frames = frames(build.level(), at);
			helper.assertTrue(frames.size() == 1, "item frames: " + frames);
			ItemFrame frame = frames.getFirst();
			helper.assertTrue(frame.getPos().equals(at) && frame.getDirection() == Direction.NORTH && frame.getItem().is(Items.DIAMOND),
					"the frame: " + frame.getPos() + " " + frame.getDirection() + " " + frame.getItem());
			helper.assertTrue(build.journal().spawned().size() == 1 && build.journal().spawned().getFirst().id().equals(frame.getUUID()),
					"the journal's entities: " + build.journal().spawned());
		});
	}

	/**
	 * Undo takes the frame down before its wall, so it never drops as an item (M5 Review Focus), and the journal's
	 * entities survive a save.
	 */
	@GameTest(structure = ARENA, maxTicks = 600)
	public void undoRemovesItemFramesFirst(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ArenaBuild build = wallWithAFrame(helper);
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> {
					helper.assertTrue(frames(level, build.world(new Pos(0, 1, 4))).size() == 1, "no frame to undo");
					UndoJournal<BlockState, CompoundTag> saved = saveAndLoad(level, build.journal());
					helper.assertValueEqual(saved.spawned(), build.journal().spawned(), "the journal's entities after a save");
					UndoRunner runner = new UndoRunner(level, saved, Tunables.BLOCKS_PER_TICK);
					JobScheduler.add(runner);
					undo.set(runner);
				})
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> {
					BlockPos at = build.world(new Pos(0, 1, 4));
					helper.assertTrue(frames(level, at).isEmpty(), "the frame is still up");
					helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(4)).isEmpty(),
							"something dropped: " + level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(4)));
					helper.assertTrue(build.state(0, 1, 5).isAir(), "the wall is still there");
				})
				.thenSucceed();
	}

	private static UndoJournal<BlockState, CompoundTag> saveAndLoad(ServerLevel level, UndoJournal<BlockState, CompoundTag> journal) {
		UUID player = UUID.randomUUID();
		UndoStore store = new UndoStore();
		store.push(player, new UndoStore.StoredBuild(level.dimension(), "frames", journal));
		Tag saved = UndoStore.CODEC.encodeStart(NbtOps.INSTANCE, store).getOrThrow();
		return UndoStore.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow().pop(player).orElseThrow().journal();
	}
}
