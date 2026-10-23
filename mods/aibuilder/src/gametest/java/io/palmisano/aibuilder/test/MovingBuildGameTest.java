package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.ArenaBuilds.ARENA;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.math.Transformation;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.anim.DisplayPose;
import io.palmisano.aibuilder.core.anim.Motion;
import io.palmisano.aibuilder.core.anim.MovingPart;
import io.palmisano.aibuilder.core.anim.Poses;
import io.palmisano.aibuilder.core.anim.Rigid;
import io.palmisano.aibuilder.core.anim.Vec3;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.mixin.BlockDisplayAccessor;
import io.palmisano.aibuilder.mixin.DisplayAccessor;
import io.palmisano.aibuilder.test.ArenaBuilds.ArenaBuild;
import io.palmisano.aibuilder.world.AnimationEngine;
import io.palmisano.aibuilder.world.AnimationStore;
import io.palmisano.aibuilder.world.Displays;
import io.palmisano.aibuilder.world.JobScheduler;
import io.palmisano.aibuilder.world.Occupancy;
import io.palmisano.aibuilder.world.UndoRunner;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionfc;
import org.joml.Vector3fc;

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
					helper.assertValueEqual(display.position(), net.minecraft.world.phys.Vec3.atLowerCornerOf(at), "where the display stands");
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

	/** A stone post with a three-block wing on its top, swinging about the post's right-hand face. */
	private static final String FLAP = """
			{"calls": [
			  {"tool": "submit_brief", "args": {"title": "a flapping wing", "style": "test"}},
			  {"tool": "fill", "args": {"box": [0, 1, 4, 0, 3, 4], "block": "stone"}},
			  {"tool": "fill", "args": {"box": [1, 3, 4, 3, 3, 4], "block": "gray_wool", "part": "wing"}},
			  {"tool": "animate", "args": {"part": "wing", "pivot": [0.5, 3, 4],
			    "motions": [{"swing": {"axis": "forward", "from": -30, "to": 40, "period": 40}}]}},
			  {"tool": "finish", "args": {"summary": "a post with a flapping wing"}}
			]}""";

	/** The wing's displays are told the pose Poses gives for one step ahead, in a build facing east. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void theWingMovesOverTime(GameTestHelper helper) {
		ArenaBuild build = start(helper, Facing.EAST, FLAP);
		helper.startSequence()
				.thenWaitUntil(() -> moving(helper, build))
				.thenExecute(() -> {
					AnimationStore.MovingBuild moving = build.animations().builds().getFirst();
					AnimationEngine engine = engine(helper, build.animations(), () -> true, Tunables.TICK_BUDGET_NANOS);
					long start = moving.key().start();
					tickAStep(engine, start + 8);
					long early = assertPosed(helper, build, moving, start + 8);
					List<Transformation> earlyPoses = transformations(build, moving);
					tickAStep(engine, start + 20);
					long later = assertPosed(helper, build, moving, start + 20);
					helper.assertTrue(later > early, "posed for " + early + " then " + later);
					helper.assertFalse(earlyPoses.equals(transformations(build, moving)), "the wing didn't move");
				})
				.thenSucceed();
	}

	/** moving_parts false: the engine leaves every display where it is. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void aFrozenWorldDoesntMove(GameTestHelper helper) {
		ArenaBuild build = start(helper, Facing.SOUTH, FLAP);
		helper.startSequence()
				.thenWaitUntil(() -> moving(helper, build))
				.thenExecute(() -> {
					AnimationStore.MovingBuild moving = build.animations().builds().getFirst();
					AtomicBoolean running = new AtomicBoolean(true);
					AnimationEngine engine = engine(helper, build.animations(), running::get, Tunables.TICK_BUDGET_NANOS);
					long start = moving.key().start();
					tickAStep(engine, start + 8);
					List<Transformation> posed = transformations(build, moving);
					helper.assertFalse(posed.stream().allMatch(pose -> pose.equals(Transformation.IDENTITY)), "the wing was never posed");
					running.set(false);
					tickAStep(engine, start + 12);
					tickAStep(engine, start + 16);
					helper.assertValueEqual(transformations(build, moving), posed, "the frozen wing");
				})
				.thenSucceed();
	}

	/** The moving builds saved with the world read back whole, and the motion carries on from them in step. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void motionCarriesOnAfterReload(GameTestHelper helper) {
		ArenaBuild build = start(helper, Facing.WEST, FLAP);
		helper.startSequence()
				.thenWaitUntil(() -> moving(helper, build))
				.thenExecute(() -> {
					Tag saved = AnimationStore.CODEC.encodeStart(NbtOps.INSTANCE, build.animations()).getOrThrow();
					AnimationStore loaded = AnimationStore.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
					helper.assertValueEqual(loaded.builds(), build.animations().builds(), "the moving builds read back");
					AnimationStore.MovingBuild moving = loaded.builds().getFirst();
					long now = moving.key().start() + 28;
					tickAStep(engine(helper, loaded, () -> true, Tunables.TICK_BUDGET_NANOS), now);
					assertPosed(helper, build, moving, now);
				})
				.thenSucceed();
	}

	/** With no time to spare, a big part's displays are posed one a tick, and every one of them is. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void aBigPartSpreadsOverTicks(GameTestHelper helper) {
		ArenaBuild build = start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "a tilting deck", "style": "test"}},
				  {"tool": "fill", "args": {"box": [-3, 1, 4, 4, 1, 11], "block": "oak_planks", "part": "deck"}},
				  {"tool": "animate", "args": {"part": "deck", "pivot": [0.5, 1, 7.5],
				    "motions": [{"swing": {"axis": "up", "from": 10, "to": 30, "period": 40}}]}},
				  {"tool": "finish", "args": {"summary": "a deck of 64 planks"}}
				]}""");
		helper.startSequence()
				.thenWaitUntil(() -> moving(helper, build))
				.thenExecute(() -> {
					AnimationStore.MovingBuild moving = build.animations().builds().getFirst();
					helper.assertValueEqual(moving.parts().getFirst().displays().size(), 64, "displays");
					long[] ticks = poseOneARound(build, moving, engine(helper, build.animations(), () -> true, 1), moving.key().start() + 40);
					helper.assertTrue(ticks[1] >= 0, "not every display was posed");
					helper.assertTrue(ticks[1] - ticks[0] >= 63, "64 displays posed in " + (ticks[1] - ticks[0] + 1) + " ticks with no time to spare");
				})
				.thenSucceed();
	}

	/**
	 * A part posed over many ticks (no time to spare) holds together: every display of a round is posed for the same
	 * time, so the part never comes apart into slices a tick apart (M7 review).
	 */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void aSpreadPartHoldsTogether(GameTestHelper helper) {
		ArenaBuild build = start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "a tilting deck", "style": "test"}},
				  {"tool": "fill", "args": {"box": [-3, 1, 4, 4, 1, 11], "block": "oak_planks", "part": "deck"}},
				  {"tool": "animate", "args": {"part": "deck", "pivot": [0.5, 1, 7.5],
				    "motions": [{"swing": {"axis": "up", "from": 10, "to": 30, "period": 40}}]}},
				  {"tool": "finish", "args": {"summary": "a deck of 64 planks"}}
				]}""");
		helper.startSequence()
				.thenWaitUntil(() -> moving(helper, build))
				.thenExecute(() -> {
					AnimationStore.MovingBuild moving = build.animations().builds().getFirst();
					long from = moving.key().start() + 40;
					long[] ticks = poseOneARound(build, moving, engine(helper, build.animations(), () -> true, 1), from);
					helper.assertTrue(ticks[1] >= 0, "not every display was posed");
					boolean together = false;
					for (long at = from; at <= ticks[1] + 4L * Tunables.ANIMATION_STEP_TICKS && !together; at++) {
						together = fits(build, moving, at);
					}
					helper.assertTrue(together, "no one time fits every display: the deck came apart");
				})
				.thenSucceed();
	}

	/** A display that's gone (killed, or taken down) is forgotten when the engine finds it missing. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void aKilledDisplayIsForgotten(GameTestHelper helper) {
		ArenaBuild build = start(helper, Facing.SOUTH, FLAP);
		helper.startSequence()
				.thenWaitUntil(() -> moving(helper, build))
				.thenExecute(() -> {
					AnimationStore.MovingBuild moving = build.animations().builds().getFirst();
					UUID killed = moving.parts().getFirst().displays().getFirst().id();
					build.level().getEntity(killed).discard();
					tickAStep(engine(helper, build.animations(), () -> true, Tunables.TICK_BUDGET_NANOS), moving.key().start() + 8);
					List<AnimationStore.PartDisplay> left = build.animations().builds().getFirst().parts().getFirst().displays();
					helper.assertValueEqual(left.size(), 2, "displays left");
					helper.assertFalse(left.stream().anyMatch(d -> d.id().equals(killed)), "the killed display is still there");
				})
				.thenSucceed();
	}

	/** Undo forgets the moving build with its displays: nothing is left for the engine to move. */
	@GameTest(structure = ARENA, maxTicks = 600)
	public void undoForgetsTheMovingBuild(GameTestHelper helper) {
		ArenaBuild build = start(helper, Facing.SOUTH, FLAP);
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> moving(helper, build))
				.thenExecute(() -> {
					UndoRunner runner = new UndoRunner(build.level(), build.journal(), Tunables.BLOCKS_PER_TICK, build.animations());
					JobScheduler.add(runner);
					undo.set(runner);
				})
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> helper.assertValueEqual(build.animations().builds(), List.of(), "moving builds after the undo"))
				.thenSucceed();
	}

	/**
	 * A refinement that gives an earlier part another motion, re-tips one of its blocks and adds one: undoing it puts
	 * the motion, the block and the part back as they were (spec v2 §4.4).
	 */
	@GameTest(structure = ARENA, maxTicks = 800)
	public void undoingARefinementPutsTheMotionBack(GameTestHelper helper) {
		ArenaBuild first = start(helper, Facing.SOUTH, FLAP);
		AtomicReference<ArenaBuild> second = new AtomicReference<>();
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> moving(helper, first))
				.thenExecute(() -> second.set(ArenaBuilds.refine(helper, first, """
						{"calls": [
						  {"tool": "submit_brief", "args": {"title": "a spinning wing", "style": "test"}},
						  {"tool": "set_blocks", "args": {"blocks": [{"pos": [2, 3, 4], "block": "black_wool"}, {"pos": [4, 3, 4], "block": "gray_wool"}],
						    "part": "wing"}},
						  {"tool": "animate", "args": {"part": "wing", "pivot": [0.5, 3, 4], "motions": [{"spin": {"axis": "forward", "period": 80}}]}},
						  {"tool": "finish", "args": {"summary": "a longer wing with a black stripe, spinning"}}
						]}""")))
				.thenWaitUntil(() -> {
					ArenaBuilds.assertDone(helper, second.get());
					AnimationStore.Part wing = first.animations().builds().getFirst().parts().getFirst();
					helper.assertTrue(wing.motion().motions().getFirst() instanceof Motion.Spin, "the wing isn't spinning");
					helper.assertValueEqual(wing.displays().size(), 4, "the wing's displays");
				})
				.thenExecute(() -> {
					Map<BlockPos, Display.BlockDisplay> displays = byCorner(helper, first);
					helper.assertValueEqual(displays.size(), 4, "displays after the refinement");
					helper.assertValueEqual(state(displays.get(first.world(new Pos(2, 3, 4)))).getBlock(), Blocks.WOOL.pick(DyeColor.BLACK), "the stripe");
					UndoRunner runner = new UndoRunner(first.level(), second.get().journal(), Tunables.BLOCKS_PER_TICK, first.animations());
					JobScheduler.add(runner);
					undo.set(runner);
				})
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> {
					AnimationStore.Part wing = first.animations().builds().getFirst().parts().getFirst();
					helper.assertValueEqual(wing.motion().motions(), List.of(new Motion.Swing("forward", -30, 40, 40, 0)), "the motion after the undo");
					helper.assertValueEqual(wing.displays().size(), 3, "the wing's displays after the undo");
					Map<BlockPos, Display.BlockDisplay> displays = byCorner(helper, first);
					helper.assertValueEqual(displays.size(), 3, "displays after the undo");
					helper.assertValueEqual(state(displays.get(first.world(new Pos(2, 3, 4)))).getBlock(), Blocks.WOOL.pick(DyeColor.GRAY),
							"the stripe after the undo");
				})
				.thenSucceed();
	}

	/** Air written with a part hides that block's display; undoing the refinement that did it shows the block again. */
	@GameTest(structure = ARENA, maxTicks = 800)
	public void airHidesAPartsBlockUntilItsUndone(GameTestHelper helper) {
		ArenaBuild first = start(helper, Facing.SOUTH, FLAP);
		AtomicReference<ArenaBuild> second = new AtomicReference<>();
		AtomicReference<UndoRunner> undo = new AtomicReference<>();
		helper.startSequence()
				.thenWaitUntil(() -> moving(helper, first))
				.thenExecute(() -> second.set(ArenaBuilds.refine(helper, first, """
						{"calls": [
						  {"tool": "submit_brief", "args": {"title": "a shorter wing", "style": "test"}},
						  {"tool": "set_blocks", "args": {"blocks": [{"pos": [3, 3, 4], "block": "air"}], "part": "wing"}},
						  {"tool": "finish", "args": {"summary": "a wing a block shorter"}}
						]}""")))
				.thenWaitUntil(() -> {
					ArenaBuilds.assertDone(helper, second.get());
					helper.assertTrue(state(byCorner(helper, first).get(first.world(new Pos(3, 3, 4)))).isAir(), "the wing's tip still shows");
				})
				.thenExecute(() -> {
					UndoRunner runner = new UndoRunner(first.level(), second.get().journal(), Tunables.BLOCKS_PER_TICK, first.animations());
					JobScheduler.add(runner);
					undo.set(runner);
				})
				.thenWaitUntil(() -> helper.assertTrue(undo.get().result().isDone(), "the undo hasn't finished"))
				.thenExecute(() -> helper.assertValueEqual(state(byCorner(helper, first).get(first.world(new Pos(3, 3, 4)))).getBlock(),
						Blocks.WOOL.pick(DyeColor.GRAY), "the tip after the undo"))
				.thenSucceed();
	}

	/** A hollow fill as a part spawns displays for its shell only: none for the air inside. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void aHollowPartHasNoAirDisplays(GameTestHelper helper) {
		ArenaBuild build = start(helper, Facing.SOUTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "a cage", "style": "test"}},
				  {"tool": "fill", "args": {"box": [-2, 1, 4, 2, 3, 6], "block": "oak_planks", "mode": "hollow", "part": "cage"}},
				  {"tool": "finish", "args": {"summary": "a hollow cage"}}
				]}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			helper.assertValueEqual(byCorner(helper, build).size(), 42, "displays");
		});
	}

	/** A saved moving build that can't be read (a facing that isn't one) costs only itself. */
	@GameTest
	public void aStoredBuildThatCantBeReadIsSkipped(GameTestHelper helper) {
		AnimationStore store = new AnimationStore();
		MovingPart swing = new MovingPart("wing", new Vec3(0.5, 3, 4), List.of(new Motion.Swing("forward", -30, 40, 40, 0)), "");
		AnimationStore.Key north = new AnimationStore.Key(UUID.randomUUID(), Level.OVERWORLD, new LocalFrame(new Pos(10, 64, -3), Facing.NORTH), 100);
		AnimationStore.Key east = new AnimationStore.Key(UUID.randomUUID(), Level.OVERWORLD, new LocalFrame(new Pos(-50, 70, 8), Facing.EAST), 200);
		for (AnimationStore.Key key : List.of(north, east)) {
			store.display(key, "wing", new AnimationStore.PartDisplay(UUID.randomUUID(), new Pos(1, 3, 4)));
			store.animate(key, swing);
		}
		String saved = AnimationStore.CODEC.encodeStart(NbtOps.INSTANCE, store).getOrThrow().toString();
		helper.assertTrue(saved.contains("\"east\""), "no east in " + saved);
		CompoundTag broken;
		try {
			broken = TagParser.parseCompoundFully(saved.replace("\"east\"", "\"sideways\""));
		} catch (CommandSyntaxException e) {
			throw new IllegalStateException(e);
		}
		AnimationStore loaded = AnimationStore.CODEC.parse(NbtOps.INSTANCE, broken).resultOrPartial(error -> { }).orElseThrow();
		helper.assertValueEqual(loaded.builds().stream().map(b -> b.key()).toList(), List.of(north), "the builds that load");
		helper.succeed();
	}

	/** check_motion reads the world: a wing that swings down through its post is told so, at the post's blocks. */
	@GameTest(structure = ARENA, maxTicks = 400)
	public void checkMotionReadsTheWorld(GameTestHelper helper) {
		ArenaBuild build = start(helper, Facing.NORTH, """
				{"calls": [
				  {"tool": "submit_brief", "args": {"title": "a wing that hits its post", "style": "test"}},
				  {"tool": "fill", "args": {"box": [0, 1, 4, 0, 3, 4], "block": "stone"}},
				  {"tool": "fill", "args": {"box": [1, 3, 4, 3, 3, 4], "block": "gray_wool", "part": "wing"}},
				  {"tool": "animate", "args": {"part": "wing", "pivot": [0.5, 3.5, 4],
				    "motions": [{"swing": {"axis": "forward", "from": 0, "to": 90, "period": 40}}]}},
				  {"tool": "check_motion", "args": {}},
				  {"tool": "finish", "args": {"summary": "a wing through its post"}}
				]}""");
		helper.succeedWhen(() -> {
			ArenaBuilds.assertDone(helper, build);
			String reply = build.reply("check_motion");
			helper.assertTrue(reply.contains("at 20 ticks [0, 1, 4] to [0, 3, 4]"), reply);
			helper.assertTrue(reply.contains("passes through 3 solid blocks: [0, 1, 4], [0, 2, 4], [0, 3, 4]."), reply);
		});
	}

	/** Passes once the build is done and its wing (or deck) has its motion in the build's store. */
	private static void moving(GameTestHelper helper, ArenaBuild build) {
		ArenaBuilds.assertDone(helper, build);
		List<AnimationStore.MovingBuild> builds = build.animations().builds();
		helper.assertValueEqual(builds.size(), 1, "moving builds");
		helper.assertFalse(builds.getFirst().parts().getFirst().motion().motions().isEmpty(), "the part has no motion yet");
	}

	private static AnimationEngine engine(GameTestHelper helper, AnimationStore store, BooleanSupplier running, long nanos) {
		return new AnimationEngine(helper.getLevel().getServer(), store, running, nanos);
	}

	/** What each of the build's first part's displays draws with, in the store's order. */
	private static List<Transformation> transformations(ArenaBuild build, AnimationStore.MovingBuild moving) {
		return moving.parts().getFirst().displays().stream()
				.map(d -> DisplayAccessor.aibuilder$transformation(build.level().getEntity(d.id()).getEntityData())).toList();
	}

	/** Ticks the engine through one step from {@code from}: every part comes due once in it, wherever its turn falls. */
	private static void tickAStep(AnimationEngine engine, long from) {
		for (long t = from; t < from + Tunables.ANIMATION_STEP_TICKS; t++) {
			engine.tick(t);
		}
	}

	/** Ticks until every display of the first part is posed: the ticks the first and the last were posed in (-1: never). */
	private static long[] poseOneARound(ArenaBuild build, AnimationStore.MovingBuild moving, AnimationEngine engine, long from) {
		int size = moving.parts().getFirst().displays().size();
		long first = -1;
		for (long t = from; t < from + size + 4L * Tunables.ANIMATION_STEP_TICKS; t++) {
			engine.tick(t);
			long posed = transformations(build, moving).stream().filter(pose -> !pose.equals(Transformation.IDENTITY)).count();
			if (posed > 0 && first < 0) {
				first = t;
			}
			if (posed == size) {
				return new long[] {first, t};
			}
		}
		return new long[] {first, -1};
	}

	/**
	 * Each display of the first part draws with what Poses gives for one time, the same for all, within a few steps after
	 * {@code after}, and glides to it over a step; that time.
	 */
	private static long assertPosed(GameTestHelper helper, ArenaBuild build, AnimationStore.MovingBuild moving, long after) {
		AnimationStore.Part part = moving.parts().getFirst();
		int step = Poses.stepTicks(part.motion().name(), moving.motions());
		for (long at = after; at <= after + 3L * step; at++) {
			if (fits(build, moving, at)) {
				for (AnimationStore.PartDisplay d : part.displays()) {
					DisplayAccessor display = (DisplayAccessor) build.level().getEntity(d.id());
					helper.assertValueEqual(display.aibuilder$transformationInterpolationDuration(), step, "the glide at " + d.block());
				}
				return at;
			}
		}
		helper.assertTrue(false, "no one time after " + after + " fits every display of " + part.motion().name() + ": " + transformations(build, moving));
		return -1;
	}

	/** Whether every display of the first part draws with what Poses gives for the time {@code at}. */
	private static boolean fits(ArenaBuild build, AnimationStore.MovingBuild moving, long at) {
		AnimationStore.Part part = moving.parts().getFirst();
		Map<String, MovingPart> motions = moving.motions();
		Facing facing = moving.key().frame().facing();
		Rigid pose = Poses.rigid(part.motion().name(), motions, at - moving.key().start(), facing);
		for (AnimationStore.PartDisplay d : part.displays()) {
			Transformation drawn = DisplayAccessor.aibuilder$transformation(build.level().getEntity(d.id()).getEntityData());
			DisplayPose expected = Poses.display(pose, d.block(), facing);
			Vector3fc t = drawn.translation();
			Quaternionfc r = drawn.leftRotation();
			boolean close = Math.abs(t.x() - expected.translation().x()) < 1e-4 && Math.abs(t.y() - expected.translation().y()) < 1e-4
					&& Math.abs(t.z() - expected.translation().z()) < 1e-4 && Math.abs(r.w() - expected.rotation().w()) < 1e-4
					&& Math.abs(r.x() - expected.rotation().x()) < 1e-4 && Math.abs(r.y() - expected.rotation().y()) < 1e-4
					&& Math.abs(r.z() - expected.rotation().z()) < 1e-4;
			if (!close) {
				return false;
			}
		}
		return true;
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
