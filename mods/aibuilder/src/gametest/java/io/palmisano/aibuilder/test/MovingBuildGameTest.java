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
					engine.tick(start + 8);
					List<Transformation> early = assertPosed(helper, build, moving, start + 8);
					engine.tick(start + 20);
					List<Transformation> later = assertPosed(helper, build, moving, start + 20);
					helper.assertFalse(early.equals(later), "the wing didn't move");
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
					engine.tick(start + 8);
					List<Transformation> posed = transformations(build, moving);
					running.set(false);
					engine.tick(start + 12);
					engine.tick(start + 16);
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
					engine(helper, loaded, () -> true, Tunables.TICK_BUDGET_NANOS).tick(now);
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
					AnimationEngine engine = engine(helper, build.animations(), () -> true, 1);
					long start = moving.key().start() + 40;
					for (int t = 0; t < 63; t++) {
						engine.tick(start + t);
					}
					long posed = transformations(build, moving).stream().filter(pose -> !pose.equals(Transformation.IDENTITY)).count();
					helper.assertTrue(posed < 64, "all 64 posed in 63 ticks with no time to spare");
					engine.tick(start + 63);
					helper.assertTrue(transformations(build, moving).stream().noneMatch(pose -> pose.equals(Transformation.IDENTITY)),
							"not every display was posed in 64 ticks");
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
					engine(helper, build.animations(), () -> true, Tunables.TICK_BUDGET_NANOS).tick(moving.key().start() + 8);
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

	/** Each display of the first part draws with what Poses gives for one step after {@code now}, gliding over a step. */
	private static List<Transformation> assertPosed(GameTestHelper helper, ArenaBuild build, AnimationStore.MovingBuild moving, long now) {
		AnimationStore.Part part = moving.parts().getFirst();
		Map<String, MovingPart> motions = moving.motions();
		Facing facing = moving.key().frame().facing();
		int step = Poses.stepTicks(part.motion().name(), motions);
		Rigid pose = Poses.rigid(part.motion().name(), motions, now + step - moving.key().start(), facing);
		for (AnimationStore.PartDisplay d : part.displays()) {
			Display.BlockDisplay display = (Display.BlockDisplay) build.level().getEntity(d.id());
			Transformation drawn = DisplayAccessor.aibuilder$transformation(display.getEntityData());
			DisplayPose expected = Poses.display(pose, d.block(), facing);
			Vector3fc t = drawn.translation();
			Quaternionfc r = drawn.leftRotation();
			boolean close = Math.abs(t.x() - expected.translation().x()) < 1e-4 && Math.abs(t.y() - expected.translation().y()) < 1e-4
					&& Math.abs(t.z() - expected.translation().z()) < 1e-4 && Math.abs(r.w() - expected.rotation().w()) < 1e-4
					&& Math.abs(r.x() - expected.rotation().x()) < 1e-4 && Math.abs(r.y() - expected.rotation().y()) < 1e-4
					&& Math.abs(r.z() - expected.rotation().z()) < 1e-4;
			helper.assertTrue(close, "the display at " + d.block() + " draws with " + t + " " + r + ", not " + expected);
			helper.assertValueEqual(((DisplayAccessor) display).aibuilder$transformationInterpolationDuration(), step, "its glide");
		}
		return transformations(build, moving);
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
