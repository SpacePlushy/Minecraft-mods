package io.palmisano.aibuilder.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.palmisano.aibuilder.AiBuilderMod;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.Placement;
import io.palmisano.aibuilder.core.place.PlacementOrder;
import io.palmisano.aibuilder.core.place.UndoJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places one build's blocks on the server tick, a budget at a time, each batch in {@link PlacementOrder}. Writes use
 * {@code /fill … strict}'s flags, so nothing reacts while the build goes up: sand doesn't fall, water doesn't flow,
 * nothing drops. A position a player stands in waits and is retried every tick. Once the end is asked for and
 * everything is placed, the finalize pass ({@link ShapePass}) gives every changed block its shape. Whatever a write
 * replaces goes into the undo journal first.
 *
 * <p>{@link #enqueue}, {@link #whenIdle}, {@link #finish} and {@link #cancel} may be called from any thread; the
 * rest runs on the server thread.
 */
public final class PlacementJob implements Tickable {
	/** {@code Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS} (818), exactly what {@code /fill … strict} uses. */
	static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS;

	private record Settling(BlockPos pos, long readyAt) {
	}

	private final ServerLevel level;
	private final Rotation rotation;
	private final UndoJournal<BlockState, CompoundTag> journal;
	private final Occupancy occupancy;
	private final int blocksPerTick;
	private final long nanosPerTick;
	/** Positions the build may change (spec §5.4); a new one past that is dropped. */
	private final long maxChanged;
	private volatile boolean capReached;
	private long overCap;
	/** One id string per block, shared by every journal entry (a 350k build would otherwise hold 350k copies). */
	private final Map<Block, String> ids = new IdentityHashMap<>();

	private final Object lock = new Object();
	private final ArrayDeque<Placement> queue = new ArrayDeque<>(); // guarded by lock
	private final List<CompletableFuture<Integer>> idleWaiters = new ArrayList<>(); // guarded by lock
	private int waitingCount; // guarded by lock
	private boolean endRequested; // guarded by lock
	private boolean cancelled; // guarded by lock

	/** Blocks held back because a player stood there. */
	private final List<Map.Entry<BlockPos, BlockState>> waiting = new ArrayList<>();
	private final CompletableFuture<Integer> ended = new CompletableFuture<>();
	private ShapePass shapePass;
	/** Block entities written recently, oldest first, waiting to have their data taken again. */
	private final ArrayDeque<Settling> settling = new ArrayDeque<>();
	private int skipped;
	private int failures;
	private volatile int placed;

	public PlacementJob(ServerLevel level, Rotation rotation, UndoJournal<BlockState, CompoundTag> journal, Occupancy occupancy,
			int blocksPerTick, long nanosPerTick, long maxChanged) {
		this.level = level;
		this.rotation = rotation;
		this.journal = journal;
		this.occupancy = occupancy;
		this.blocksPerTick = blocksPerTick;
		this.nanosPerTick = nanosPerTick;
		this.maxChanged = maxChanged;
	}

	/** The build has changed {@code maxChanged} positions: what's queued for new ones goes nowhere. Any thread. */
	public boolean capReached() {
		return capReached;
	}

	/** Placements queued and not yet placed, those waiting for a player included. Any thread. */
	public long pending() {
		synchronized (lock) {
			return queue.size() + waitingCount;
		}
	}

	/** Queues one write call's blocks, supports first. Dropped once the job is ending. */
	public void enqueue(List<Placement> batch) {
		List<Placement> sorted = PlacementOrder.sort(batch);
		synchronized (lock) {
			if (!endRequested) {
				queue.addAll(sorted);
			}
		}
	}

	/** Positions written so far, each counted once however often it's rewritten; a door's two halves count two. */
	public int placed() {
		return placed;
	}

	/** Everything queued is placed and nothing is waiting for a player to move. */
	public boolean idle() {
		synchronized (lock) {
			return idleLocked();
		}
	}

	/** Completes, with {@link #placed()}, once the job is next {@link #idle()}. */
	public CompletableFuture<Integer> whenIdle() {
		synchronized (lock) {
			if (idleLocked()) {
				return CompletableFuture.completedFuture(placed);
			}
			CompletableFuture<Integer> waiter = new CompletableFuture<>();
			idleWaiters.add(waiter);
			return waiter;
		}
	}

	/**
	 * Asks the job to end once everything queued is placed: positions a player still stands in are given up, then
	 * the finalize pass runs. The future completes on the server thread with the number given up.
	 */
	public CompletableFuture<Integer> finish() {
		synchronized (lock) {
			endRequested = true;
		}
		return ended;
	}

	/** Drops everything not yet placed and ends the job; what's placed stays, finalized, and the journal keeps it. */
	public void cancel() {
		synchronized (lock) {
			cancelled = true;
			endRequested = true;
			queue.clear();
		}
	}

	/** The same future {@link #finish()} returns, without asking the job to end. */
	public CompletableFuture<Integer> whenEnded() {
		return ended;
	}

	/** Everything is placed (or dropped) and finalized. */
	public boolean ended() {
		return ended.isDone();
	}

	@Override
	public boolean tick() {
		if (ended.isDone()) {
			return true;
		}
		long deadline = System.nanoTime() + nanosPerTick;
		settle();
		if (shapePass == null) {
			boolean drop;
			synchronized (lock) {
				drop = cancelled;
			}
			if (drop) {
				waiting.clear();
			} else {
				placeQueued(deadline, placeWaiting());
			}
			boolean end;
			synchronized (lock) {
				waitingCount = waiting.size();
				end = endRequested && queue.isEmpty();
			}
			if (!end) {
				releaseIdleWaiters();
				return false;
			}
			skipped = waiting.size();
			waiting.clear();
			synchronized (lock) {
				waitingCount = 0;
			}
			releaseIdleWaiters();
			shapePass = new ShapePass(level, journal.positions().stream().map(Positions::toBlockPos).toList(), this::write);
		}
		if (!shapePass.run(deadline) || !settling.isEmpty()) {
			// The last block entities settle before the build counts as done.
			return false;
		}
		if (failures > 0) {
			AiBuilderMod.LOG.warn("{} placements failed in one build; the first was logged", failures);
		}
		if (overCap > 0) {
			AiBuilderMod.LOG.info("{} placements went past the build's cap of {} changed blocks and were dropped", overCap, maxChanged);
		}
		ended.complete(skipped);
		return true;
	}

	@Override
	public void failed(RuntimeException error) {
		cancel();
		waiting.clear();
		synchronized (lock) {
			waitingCount = 0;
		}
		releaseIdleWaiters();
		ended.completeExceptionally(error);
	}

	/** Places what waited for a player, where they've moved away; returns the tick's budget left. */
	private int placeWaiting() {
		int budget = blocksPerTick;
		for (Iterator<Map.Entry<BlockPos, BlockState>> it = waiting.iterator(); it.hasNext() && budget > 0;) {
			Map.Entry<BlockPos, BlockState> entry = it.next();
			if (!occupancy.occupied(entry.getKey())) {
				it.remove();
				placeBlock(entry.getKey(), entry.getValue());
				budget--;
			}
		}
		return budget;
	}

	private void placeQueued(long deadline, int budget) {
		while (budget > 0 && System.nanoTime() < deadline) {
			Placement next;
			synchronized (lock) {
				next = queue.peek();
			}
			if (next == null) {
				return;
			}
			budget -= place(next);
			// Taken off only now, so the job never looks idle while its last block is still going in.
			synchronized (lock) {
				queue.poll();
			}
		}
	}

	/** Writes one placement, both halves of a two-block block; returns how much of the tick's budget it used. */
	private int place(Placement placement) {
		try {
			BlockPos pos = Positions.toBlockPos(placement.pos());
			if (placement.onlyReplacing() != null && !matches(level.getBlockState(pos), placement.onlyReplacing())) {
				return 0;
			}
			int written = 0;
			for (Map.Entry<BlockPos, BlockState> entry : StateFactory.states(placement.block(), rotation, pos)) {
				if (occupancy.occupied(entry.getKey())) {
					waiting.add(entry);
				} else if (placeBlock(entry.getKey(), entry.getValue())) {
					written++;
				}
			}
			return Math.max(written, 1);
		} catch (RuntimeException e) {
			if (failures++ == 0) {
				AiBuilderMod.LOG.warn("Couldn't place {} at {}", placement.block().info().id(), placement.pos(), e);
			}
			return 1;
		}
	}

	/** One block of a placement; a position the build hadn't changed before adds one to {@link #placed()}. */
	private boolean placeBlock(BlockPos pos, BlockState state) {
		int known = journal.size();
		boolean wrote = write(pos, state);
		if (journal.size() > known) {
			placed++;
		}
		return wrote;
	}

	/** Journals what's there, then writes; false when that state is already there, or the build is at its cap. */
	private boolean write(BlockPos pos, BlockState state) {
		BlockState old = level.getBlockState(pos);
		if (old == state) {
			return false;
		}
		Pos at = Positions.toPos(pos);
		if (!journal.has(at) && journal.size() >= maxChanged) {
			capReached = true;
			overCap++;
			return false;
		}
		BlockEntity before = level.getBlockEntity(pos);
		journal.beforeWrite(at, old, before == null ? null : before.saveWithFullMetadata(level.registryAccess()));
		level.setBlock(pos, state, FLAGS);
		BlockState now = level.getBlockState(pos);
		BlockEntity after = level.getBlockEntity(pos);
		journal.afterWrite(at, id(now.getBlock()), BlockEntityHash.of(after, level.registryAccess()));
		if (journal.size() >= maxChanged) {
			capReached = true;
		}
		if (after != null) {
			settling.add(new Settling(pos, level.getGameTime() + Tunables.BLOCK_ENTITY_SETTLE_TICKS));
		}
		return true;
	}

	/**
	 * Takes each new block entity's data again once it has settled: some change on their first tick (a hopper's
	 * transfer cooldown), and undo must still see them as the build's own. Soon enough that the player can't have
	 * touched it yet.
	 */
	private void settle() {
		long now = level.getGameTime();
		while (!settling.isEmpty() && settling.peekFirst().readyAt() <= now) {
			BlockPos pos = settling.pollFirst().pos();
			Pos at = Positions.toPos(pos);
			String written = journal.writtenBlock(at);
			BlockEntity entity = level.getBlockEntity(pos);
			if (entity != null && written != null && written.equals(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString())) {
				journal.afterWrite(at, written, BlockEntityHash.of(entity, level.registryAccess()));
			}
		}
	}

	private String id(Block block) {
		return ids.computeIfAbsent(block, b -> BuiltInRegistries.BLOCK.getKey(b).toString());
	}

	/** Whether {@code current} is the block id, or carries the {@code #tag}, that a replace-fill asked for. */
	static boolean matches(BlockState current, String filter) {
		boolean tag = filter.startsWith("#");
		Identifier id = Identifier.tryParse(tag ? filter.substring(1) : filter);
		if (id == null) {
			return false;
		}
		return tag ? current.is(TagKey.create(Registries.BLOCK, id)) : BuiltInRegistries.BLOCK.getKey(current.getBlock()).equals(id);
	}

	private boolean idleLocked() {
		return queue.isEmpty() && waitingCount == 0;
	}

	/** Completes the waiters outside the lock, so nothing chained on them runs while it's held. */
	private void releaseIdleWaiters() {
		List<CompletableFuture<Integer>> ready;
		synchronized (lock) {
			if (!idleLocked() || idleWaiters.isEmpty()) {
				return;
			}
			ready = List.copyOf(idleWaiters);
			idleWaiters.clear();
		}
		ready.forEach(waiter -> waiter.complete(placed));
	}
}
