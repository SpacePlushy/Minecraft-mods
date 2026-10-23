package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.ArenaBuilds.ARENA;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.mixin.BlockDisplayAccessor;
import io.palmisano.aibuilder.test.ArenaBuilds.ArenaBuild;
import io.palmisano.aibuilder.world.Displays;
import io.palmisano.aibuilder.world.JobScheduler;
import io.palmisano.aibuilder.world.Occupancy;
import io.palmisano.aibuilder.world.UndoRunner;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Moving parts in a real world (spec v2 §4.1): displays where the blocks would be, turned as they would be, undone. */
public class MovingBuildGameTest {
	/** A 3×1×2 part is six displays at those blocks' corners, each showing its block; no block changes there. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void aPartSpawnsDisplaysNotBlocks(GameTestHelper helper) {
		ArenaBuild build = start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "a moving slab", "style": "test"}},
				  {"tool": "fill", "args": {"box": [-1, 3, 4, 1, 3, 4], "block": "oak_planks", "part": "slab"}},
				  {"tool": "fill", "args": {"box": [-1, 3, 5, 1, 3, 5], "block": "stone", "part": "slab"}},
				  {"tool": "finish", "args": {"summary": "six moving blocks"}}
				]}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			Map<BlockPos, Display.BlockDisplay> displays = byCorner(helper, build);
			helper.assertValueEqual(displays.size(), 6, "displays");
			for (int x = -1; x <= 1; x++) {
				for (int z = 4; z <= 5; z++) {
					BlockPos at = build.world(new Pos(x, 3, z));
					Display.BlockDisplay display = displays.get(at);
					helper.assertTrue(display != null, "no display at " + at + ": " + displays.keySet());
					helper.assertValueEqual(display.position(), Vec3.atLowerCornerOf(at), "where the display stands");
					helper.assertValueEqual(state(display), (z == 4 ? Blocks.OAK_PLANKS : Blocks.STONE).defaultBlockState(), "what it shows");
					helper.assertTrue(display.entityTags().contains(Displays.TAG), "untagged: " + display.entityTags());
					helper.assertTrue(build.level().getBlockState(at).isAir(), "a block went in at " + at);
				}
			}
			helper.assertValueEqual(build.journal().size(), 0, "blocks the build changed");
			helper.assertValueEqual(build.journal().spawned().stream().filter(s -> s.kind().equals(UndoJournal.Spawned.DISPLAY)).count(), 6L,
					"displays journaled");
		});
	}

	/** Stairs in a part, in a build facing east, look as the same stairs built as a block. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void aPartsFacingsTurnAsBlocksDo(GameTestHelper helper) {
		ArenaBuild build = start(helper, Facing.EAST, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "two steps", "style": "test"}},
				  {"tool": "set_blocks", "args": {"blocks": [{"pos": [0, 1, 4], "block": "oak_stairs", "state": {"facing": "forward", "half": "top"}}]}},
				  {"tool": "set_blocks", "args": {"blocks": [{"pos": [3, 1, 4], "block": "oak_stairs", "state": {"facing": "forward", "half": "top"}}],
				    "part": "step"}},
				  {"tool": "finish", "args": {"summary": "a step and a moving step"}}
				]}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			BlockState block = build.state(0, 1, 4);
			helper.assertValueEqual(block.getValue(StairBlock.FACING), Direction.EAST, "the block's facing");
			helper.assertValueEqual(block.getValue(StairBlock.HALF), Half.TOP, "the block's half");
			Display.BlockDisplay display = byCorner(helper, build).get(build.world(new Pos(3, 1, 4)));
			helper.assertTrue(display != null, "no display");
			helper.assertValueEqual(state(display), block, "the display's state");
		});
	}

	/** The same spot of a part given again shows the later block, as a block would: one display, never two. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void aPartsLaterBlockReplacesItsEarlier(GameTestHelper helper) {
		ArenaBuild build = start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "a wing", "style": "test"}},
				  {"tool": "fill", "args": {"box": [0, 3, 4, 2, 3, 4], "block": "gray_wool", "part": "wing"}},
				  {"tool": "set_blocks", "args": {"blocks": [{"pos": [2, 3, 4], "block": "black_wool"}], "part": "wing"}},
				  {"tool": "finish", "args": {"summary": "a wing with a black tip"}}
				]}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			Map<BlockPos, Display.BlockDisplay> displays = byCorner(helper, build);
			helper.assertValueEqual(displays.size(), 3, "displays");
			helper.assertValueEqual(state(displays.get(build.world(new Pos(2, 3, 4)))).getBlock(), Blocks.WOOL.pick(DyeColor.BLACK),
					"the tip");
			helper.assertValueEqual(state(displays.get(build.world(new Pos(0, 3, 4)))).getBlock(), Blocks.WOOL.pick(DyeColor.GRAY),
					"the root");
		});
	}

	/** Undo takes every display down, with the build's blocks. */
	@GameTest(structure = ARENA, maxTicks = 600)
	public void undoTakesTheDisplaysDown(GameTestHelper helper) {
		ArenaBuild build = start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "a body and a wing", "style": "test"}},
				  {"tool": "fill", "args": {"box": [0, 1, 4, 0, 2, 4], "block": "stone"}},
				  {"tool": "fill", "args": {"box": [1, 2, 4, 3, 2, 4], "block": "gray_wool", "part": "wing"}},
				  {"tool": "finish", "args": {"summary": "a post with a wing"}}
				]}""");
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> ArenaBuilds.assertDone(helper, build))
				.thenExecute(() -> {
					helper.assertValueEqual(byCorner(helper, build).size(), 3, "displays before the undo");
					UndoRunner runner = new UndoRunner(build.level(), build.journal(), Tunables.BLOCKS_PER_TICK);
					JobScheduler.add(runner);
					undo.set(runner);
				})
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> {
					helper.assertValueEqual(displays(build).size(), 0, "displays after the undo");
					helper.assertTrue(build.state(0, 1, 4).isAir() && build.state(0, 2, 4).isAir(), "the post is still there");
					helper.assertValueEqual(undo.get().result().join().kept(), 0, "kept");
				})
				.thenSucceed();
	}

	private static ArenaBuild start(GameTestHelper helper, Facing facing, String script) {
		return ArenaBuilds.start(helper, facing, script, Occupancy.players(helper.getLevel()), Tunables.BLOCKS_PER_TICK);
	}

	/** Every display left in or near the build's allowed box, removed ones aside. */
	static List<Display.BlockDisplay> displays(ArenaBuild build) {
		AABB box = AABB.encapsulatingFullBlocks(build.world(ArenaBuilds.ALLOWED.min()), build.world(ArenaBuilds.ALLOWED.max())).inflate(1);
		return build.level().getEntitiesOfClass(Display.BlockDisplay.class, box, display -> !display.isRemoved());
	}

	/** The build's displays by the block whose corner each stands at; two at one spot fail the test. */
	static Map<BlockPos, Display.BlockDisplay> byCorner(GameTestHelper helper, ArenaBuild build) {
		Map<BlockPos, Display.BlockDisplay> out = new HashMap<>();
		for (Display.BlockDisplay display : displays(build)) {
			Display.BlockDisplay before = out.put(display.blockPosition(), display);
			helper.assertTrue(before == null, "two displays at " + display.blockPosition());
		}
		return out;
	}

	static BlockState state(Display.BlockDisplay display) {
		return ((BlockDisplayAccessor) display).aibuilder$blockState();
	}
}
