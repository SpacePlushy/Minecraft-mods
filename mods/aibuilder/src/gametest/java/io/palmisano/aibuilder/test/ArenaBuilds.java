package io.palmisano.aibuilder.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.agent.BuildHandle;
import io.palmisano.aibuilder.core.agent.BuildRequest;
import io.palmisano.aibuilder.core.agent.ScriptedBackend;
import io.palmisano.aibuilder.core.blocks.Validator;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.tools.Budget;
import io.palmisano.aibuilder.core.tools.ToolExecutor;
import io.palmisano.aibuilder.core.tools.ToolResult;
import io.palmisano.aibuilder.world.BuildTickets;
import io.palmisano.aibuilder.world.JobScheduler;
import io.palmisano.aibuilder.world.McBlockCatalog;
import io.palmisano.aibuilder.world.McBuildSink;
import io.palmisano.aibuilder.world.McWorldReader;
import io.palmisano.aibuilder.world.Occupancy;
import io.palmisano.aibuilder.world.PlacementJob;
import io.palmisano.aibuilder.world.Positions;
import io.palmisano.aibuilder.world.StateFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Runs a scripted build in the {@code aibuilder-test:arena} structure through the real tools, placement job and
 * finalize pass. The frame's origin is the arena's centre moved back 4 along the facing, so every build in these
 * tests stays inside the 16×12×16 arena whichever way it faces.
 */
final class ArenaBuilds {
	static final String ARENA = "aibuilder-test:arena";
	/** The allowed box (local) for arena builds. */
	static final Box ALLOWED = Box.of(-7, -1, 0, 7, 11, 15);

	private ArenaBuilds() {
	}

	static LocalFrame frame(GameTestHelper helper, Facing facing) {
		BlockPos origin = helper.absolutePos(new BlockPos(8, 0, 8)).offset(-4 * facing.forwardX(), 0, -4 * facing.forwardZ());
		return new LocalFrame(Positions.toPos(origin), facing);
	}

	/** Where a local position lands for a build facing {@code facing}, before the build starts. */
	static BlockPos worldPos(GameTestHelper helper, Facing facing, Pos local) {
		return Positions.toBlockPos(frame(helper, facing).toWorld(local));
	}

	static ArenaBuild start(GameTestHelper helper, Facing facing, String scriptJson, Occupancy occupancy, int blocksPerTick) {
		ServerLevel level = helper.getLevel();
		LocalFrame frame = frame(helper, facing);
		UndoJournal<BlockState, CompoundTag> journal = new UndoJournal<>();
		PlacementJob job = new PlacementJob(level, StateFactory.rotation(facing), journal, occupancy, blocksPerTick,
				Tunables.TICK_BUDGET_NANOS);
		Set<ChunkPos> chunks = BuildTickets.covering(Positions.toBlockPos(frame.toWorld(ALLOWED.min())),
				Positions.toBlockPos(frame.toWorld(ALLOWED.max())));
		BuildTickets.hold(level, chunks);
		job.whenEnded().whenComplete((skipped, error) -> BuildTickets.release(level, chunks));
		JobScheduler.add(job);

		McBuildSink sink = new McBuildSink(job, brief -> {
		}, summary -> job.finish());
		McWorldReader world = new McWorldReader(level, frame, ALLOWED, SizeTier.SMALL, () -> null, List.of());
		ToolExecutor tools = new ToolExecutor(new Validator(McBlockCatalog.get(), Validator.DEFAULT_BANNED), frame, ALLOWED, SizeTier.SMALL,
				SizeTier.SMALL.maxChangedBlocks(), sink, new Budget(Tunables.MAX_TOOL_CALLS), world);
		Executor worker = task -> Thread.ofPlatform().daemon().name("aibuilder-arena-build").start(task);
		ScriptedBackend backend = new ScriptedBackend(scriptJson, worker);
		BuildHandle handle = backend.start(new BuildRequest("arena test", SizeTier.SMALL), tools);
		// A script that throws never reaches finish: end the job anyway, as BuildManager will.
		handle.outcome().thenRun(() -> job.finish());
		return new ArenaBuild(level, frame, job, journal, backend, handle);
	}

	/** The demo house the {@code /aibuild test} command builds. */
	static String demoHouse() {
		try (InputStream in = ArenaBuilds.class.getResourceAsStream("/aibuilder/scripts/demo_house.json")) {
			if (in == null) {
				throw new IllegalStateException("demo_house.json is missing");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Passes once the build has finished, its finalize pass included, and every call in its script was accepted. */
	static void assertDone(GameTestHelper helper, ArenaBuild build) {
		helper.assertTrue(build.done(), "the build hasn't finished");
		helper.assertTrue(build.errors().isEmpty(), "the script had errors: " + build.errors());
	}

	record ArenaBuild(ServerLevel level, LocalFrame frame, PlacementJob job, UndoJournal<BlockState, CompoundTag> journal,
			ScriptedBackend backend, BuildHandle handle) {
		BlockPos world(Pos local) {
			return Positions.toBlockPos(frame.toWorld(local));
		}

		BlockState state(int x, int y, int z) {
			return level.getBlockState(world(new Pos(x, y, z)));
		}

		/** The script has run and the job has placed everything and finalized. */
		boolean done() {
			return handle.outcome().isDone() && job.ended();
		}

		void cancel() {
			handle.cancel();
			job.cancel();
		}

		List<String> errors() {
			return backend.errors().stream().map(ToolResult::text).toList();
		}
	}
}
