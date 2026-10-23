package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import io.palmisano.aibuilder.core.blocks.PlacementPass;

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
 * Takes a build back (spec §10): first the item frames it hung (so none drops when its wall goes) and its moving parts'
 * displays (spec v2 §4.1), then every position that still holds the block the build left there, with the same
 * block-entity data, gets its original back, contents included; anything the player has changed since stays and is
 * counted. Supports first, bottom-up, with the build's
 * own flags, then the finalize pass. Keeps every chunk the journal touches loaded and waits for them (and for their
 * entities, which load on their own), so it works however far away the build is.
 */
public final class UndoRunner implements Tickable {
	public record UndoResult(int restored, int kept) {
	}

	private final ServerLevel level;
	private final UndoJournal<BlockState, CompoundTag> journal;
	private final AnimationStore animations;
	private final int blocksPerTick;
	private final List<Pos> order;
	private final Set<ChunkPos> chunks;
	private final CompletableFuture<Void> loaded;
	private final List<BlockPos> restoredPositions = new ArrayList<>();
	private final CompletableFuture<UndoResult> result = new CompletableFuture<>();
	private final Set<ChunkPos> frameChunks = new LinkedHashSet<>();
	private ShapePass shapePass;
	private long framesDeadline;
	private boolean framesDown;
	private int next;
	private int restored;
	private int kept;
	private boolean loggedFailure;

	/** Server thread: the tickets go on here. The build's moving parts are the world's. */
	public UndoRunner(ServerLevel level, UndoJournal<BlockState, CompoundTag> journal, int blocksPerTick) {
		this(level, journal, blocksPerTick, AnimationStore.get(level.getServer()));
	}

	/** Server thread: the tickets go on here; the displays taken down are forgotten in {@code animations}. */
	public UndoRunner(ServerLevel level, UndoJournal<BlockState, CompoundTag> journal, int blocksPerTick, AnimationStore animations) {
		this.level = level;
		this.journal = journal;
		this.animations = animations;
		this.blocksPerTick = blocksPerTick;
		// Each position's pass worked out once: in the comparator it ran on every comparison (seconds for a 350k undo).
		Map<Pos, PlacementPass> passes = new HashMap<>();
		journal.positions().forEach(p -> passes.put(p, McBlockCatalog.pass(journal.original(p).getBlock())));
		this.order = journal.positions().stream().sorted(Comparator.comparing((Pos p) -> passes.get(p)).thenComparingInt(Pos::y)).toList();
		Set<ChunkPos> touched = new LinkedHashSet<>();
		order.forEach(p -> touched.add(ChunkPos.containing(Positions.toBlockPos(p))));
		journal.spawned().forEach(s -> frameChunks.add(ChunkPos.containing(Positions.toBlockPos(s.pos()))));
		touched.addAll(frameChunks);
		this.chunks = touched;
		this.loaded = BuildTickets.load(level, chunks);
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
		if (!framesDown) {
			// A chunk's entities load after its blocks; give them a while, then carry on without the ones still away.
			if (framesDeadline == 0) {
				framesDeadline = System.nanoTime() + Tunables.UNDO_ENTITY_WAIT_SECONDS * 1_000_000_000L;
			}
			if (!frameChunks.stream().allMatch(c -> level.areEntitiesLoaded(c.pack())) && System.nanoTime() < framesDeadline) {
				return false;
			}
			kept += ItemFrames.takeDown(level, journal.spawned());
			Displays.takeDown(level, journal.spawned());
			Set<UUID> displays = journal.spawned().stream().filter(s -> s.kind().equals(UndoJournal.Spawned.DISPLAY)).map(UndoJournal.Spawned::id)
					.collect(Collectors.toSet());
			if (!displays.isEmpty()) {
				animations.forget(displays);
			}
			// A refinement's changes to an earlier build's parts: the blocks they showed, the motions they had.
			journal.reshown().forEach(r -> Displays.show(level, r.display(), r.before()));
			journal.reanimated().forEach(r -> animations.restore(r.build(), r.before()));
			framesDown = true;
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
		BuildTickets.unload(level, chunks);
		result.complete(new UndoResult(restored, kept));
		return true;
	}

	@Override
	public void failed(RuntimeException error) {
		BuildTickets.unload(level, chunks);
		result.completeExceptionally(error);
	}

	/** A position given back what it held before a build, block entity data included, with the build's own flags. */
	static void putBack(ServerLevel level, BlockPos pos, BlockState original, CompoundTag data) {
		level.setBlock(pos, original, PlacementJob.FLAGS);
		if (data != null && level.getBlockEntity(pos) instanceof BlockEntity restored) {
			restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), data));
			restored.setChanged();
			level.sendBlockUpdated(pos, original, original, Block.UPDATE_CLIENTS);
		}
	}

	private void restore(Pos at) {
		BlockPos pos = Positions.toBlockPos(at);
		try {
			BlockState current = level.getBlockState(pos);
			BlockEntity entity = level.getBlockEntity(pos);
			BlockState original = journal.original(at);
			CompoundTag data = journal.originalData(at);
			if (current == original && (entity == null ? data == null
					: data != null && BlockEntityHash.of(entity, level.registryAccess()) == BlockEntityHash.of(data))) {
				// Already as it was before the build: an earlier undo, cut short by a crash, got this far.
				restored++;
				restoredPositions.add(pos);
				return;
			}
			int hash = BlockEntityHash.of(entity, level.registryAccess());
			if (journal.decide(at, BuiltInRegistries.BLOCK.getKey(current.getBlock()).toString(), hash) == RestoreDecision.KEEP) {
				kept++;
				return;
			}
			putBack(level, pos, original, data);
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
