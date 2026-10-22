package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.palmisano.aibuilder.AiBuilderMod;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.RestoreDecision;
import io.palmisano.aibuilder.core.place.UndoJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;

/**
 * Takes a build back (spec §10): every position that still holds the block the build left there, with the same
 * block-entity data, gets its original back, contents included; anything the player has changed since stays and is
 * counted. Supports first, bottom-up, with the build's own flags, then the finalize pass. Holds tickets on every
 * chunk the journal touches and waits for them to load, so it works however far away the build is.
 */
public final class UndoRunner implements Tickable {
	public record UndoResult(int restored, int kept) {
	}

	private final ServerLevel level;
	private final UndoJournal<BlockState, CompoundTag> journal;
	private final int blocksPerTick;
	private final List<Pos> order;
	private final Set<ChunkPos> chunks;
	private final CompletableFuture<Void> loaded;
	private final List<BlockPos> restoredPositions = new ArrayList<>();
	private final CompletableFuture<UndoResult> result = new CompletableFuture<>();
	private ShapePass shapePass;
	private int next;
	private int restored;
	private int kept;
	private boolean loggedFailure;

	/** Server thread: the tickets go on here. */
	public UndoRunner(ServerLevel level, UndoJournal<BlockState, CompoundTag> journal, int blocksPerTick) {
		this.level = level;
		this.journal = journal;
		this.blocksPerTick = blocksPerTick;
		this.order = journal.positions().stream()
				.sorted(Comparator.comparing((Pos p) -> McBlockCatalog.pass(journal.original(p).getBlock())).thenComparingInt(Pos::y))
				.toList();
		Set<ChunkPos> touched = new LinkedHashSet<>();
		order.forEach(p -> touched.add(ChunkPos.containing(Positions.toBlockPos(p))));
		this.chunks = touched;
		this.loaded = BuildTickets.holdAndLoad(level, chunks);
	}

	/** Completes on the server thread once everything is restored and finalized. */
	public CompletableFuture<UndoResult> result() {
		return result;
	}

	@Override
	public boolean tick() {
		if (result.isDone()) {
			return true;
		}
		if (!loaded.isDone()) {
			return false;
		}
		long deadline = System.nanoTime() + Tunables.TICK_BUDGET_NANOS;
		if (shapePass == null) {
			for (int budget = blocksPerTick; next < order.size() && budget > 0 && System.nanoTime() < deadline; budget--) {
				restore(order.get(next++));
			}
			if (next < order.size()) {
				return false;
			}
			shapePass = new ShapePass(level, restoredPositions, (pos, state) -> level.setBlock(pos, state, PlacementJob.FLAGS));
		}
		if (!shapePass.run(deadline)) {
			return false;
		}
		BuildTickets.release(level, chunks);
		result.complete(new UndoResult(restored, kept));
		return true;
	}

	@Override
	public void failed(RuntimeException error) {
		BuildTickets.release(level, chunks);
		result.completeExceptionally(error);
	}

	private void restore(Pos at) {
		BlockPos pos = Positions.toBlockPos(at);
		try {
			BlockState current = level.getBlockState(pos);
			BlockEntity entity = level.getBlockEntity(pos);
			int hash = entity == null ? 0 : entity.saveWithFullMetadata(level.registryAccess()).hashCode();
			if (journal.decide(at, BuiltInRegistries.BLOCK.getKey(current.getBlock()).toString(), hash) == RestoreDecision.KEEP) {
				kept++;
				return;
			}
			BlockState original = journal.original(at);
			level.setBlock(pos, original, PlacementJob.FLAGS);
			CompoundTag data = journal.originalData(at);
			if (data != null && level.getBlockEntity(pos) instanceof BlockEntity restoredEntity) {
				restoredEntity.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
				restoredEntity.setChanged();
				level.sendBlockUpdated(pos, original, original, Block.UPDATE_CLIENTS);
			}
			restored++;
			restoredPositions.add(pos);
		} catch (RuntimeException e) {
			kept++;
			if (!loggedFailure) {
				loggedFailure = true;
				AiBuilderMod.LOG.warn("Couldn't undo the block at {}", pos, e);
			}
		}
	}
}
