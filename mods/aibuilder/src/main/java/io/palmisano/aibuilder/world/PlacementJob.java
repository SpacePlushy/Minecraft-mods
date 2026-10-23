package io.palmisano.aibuilder.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import io.palmisano.aibuilder.AiBuilderMod;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.blocks.BlockEntityData;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.Placement;
import io.palmisano.aibuilder.core.place.PlacementOrder;
import io.palmisano.aibuilder.core.place.UndoJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeverBlock;
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

	/** Hears of each display the job makes for a moving part: the part, the display, the block it stands at. */
	public interface DisplayListener {
		void shown(String part, UUID display, BlockPos at);
	}

	/** A block held back because a player stood there, with its block entity's data (place_block_entity) if any. */
	private record Held(BlockPos pos, BlockState state, BlockEntityData data) {
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
	private volatile MachineRules machines;
	private PowerUp powerUp;
	/** Components the power-up pass has started (spec v2 §5.1): never twice, until a tool writes there again. */
	private final Set<BlockPos> powered = new HashSet<>();
	/** Levers a test had to leave flipped (a player stood in a piston's way), with how they go back once the way is clear. */
	private final Map<BlockPos, Boolean> leverRestores = new LinkedHashMap<>();
	/** A piston a running test waits on while a player stands in its way; null when none. */
	private volatile BlockPos testWaitingFor;
	private final List<BlockPos> takenOut = new ArrayList<>();
	/** One id string per block, shared by every journal entry (a 350k build would otherwise hold 350k copies). */
	private final Map<Block, String> ids = new IdentityHashMap<>();
	/** Each moving part's displays by the block they stand at, so a spot given again shows the later block. */
	private final Map<String, Map<BlockPos, UUID>> shown = new HashMap<>();
	/** The displays this job made; the others in {@link #shown} are an earlier build's (a refinement's parts). */
	private final Set<UUID> made = new HashSet<>();
	private volatile DisplayListener onDisplay = (part, display, at) -> {
	};

	private final Object lock = new Object();
	private final ArrayDeque<Placement> queue = new ArrayDeque<>(); // guarded by lock
	private final List<CompletableFuture<Integer>> idleWaiters = new ArrayList<>(); // guarded by lock
	private int waitingCount; // guarded by lock
	private boolean endRequested; // guarded by lock
	private boolean cancelled; // guarded by lock

	/** Blocks held back because a player stood there. */
	private final List<Held> waiting = new ArrayList<>();
	private final CompletableFuture<Integer> ended = new CompletableFuture<>();
	private ShapePass shapePass;
	/** Block entities written recently, oldest first, waiting to have their data taken again. */
	private final ArrayDeque<Settling> settling = new ArrayDeque<>();
	private int skipped;
	private int failures;
	private volatile int placed;
	private volatile PlacementFeel feel = PlacementFeel.STANDARD;
	/** Blocks this tick that got the feel. */
	private int felt;

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

	/**
	 * An earlier build's displays by part and block (a refinement's, spec v2 §4.4): a spot one stands at shows the later
	 * block, as one of this job's would, and the journal keeps what it showed before. Server thread, before the job starts.
	 */
	public void knownDisplays(Map<String, Map<BlockPos, UUID>> earlier) {
		earlier.forEach((part, displays) -> shown.computeIfAbsent(part, p -> new HashMap<>()).putAll(displays));
	}

	/**
	 * The build's redstone starts once everything is in (spec v2 §5.1): first what breaks the machine rules (spec v2
	 * §5.5) is put back as it was, then the power-up pass. Without rules (a test's bare job) there's neither. Any
	 * thread, before the job starts.
	 */
	public void machine(MachineRules rules) {
		rules.started(powered);
		this.machines = rules;
	}

	/** What the power-up pass has started, for check_build's rules. Server thread. */
	Set<BlockPos> started() {
		return powered;
	}

	/** The piston a running test waits on while a player stands in its way, or null. Server thread. */
	void testWaiting(BlockPos piston) {
		testWaitingFor = piston;
	}

	/** Levers a test had to leave flipped: the next power-up pass puts them back once no player is in the way. Server thread. */
	void putBackLater(Map<BlockPos, Boolean> levers) {
		leverRestores.putAll(levers);
	}

	/** The build was cancelled. Any thread. */
	boolean cancelled() {
		synchronized (lock) {
			return cancelled;
		}
	}

	/** The machine rules it was given; null for a bare job. */
	MachineRules machines() {
		return machines;
	}

	Occupancy occupancy() {
		return occupancy;
	}

	/** A power-up pass over what isn't started yet (a test's, before it runs): it shares the job's record of what is. */
	PowerUp newPass() {
		return new PowerUp(level, machines, occupancy, powered, leverRestores, journal.positions());
	}

	/** A piston of the build that waits to start while a player stands in its way; null when none does. Server thread. */
	public BlockPos waitingForPlayer() {
		BlockPos test = testWaitingFor;
		return test != null ? test : powerUp == null ? null : powerUp.waitingFor();
	}

	/** What broke the machine rules and was put back as it was before the build. Server thread. */
	public List<BlockPos> takenOut() {
		return List.copyOf(takenOut);
	}

	/** False when the power-up pass gave up waiting for a player, and the machine stays as placed. Server thread. */
	public boolean machineStarted() {
		return powerUp == null || !powerUp.gaveUp();
	}

	/** Who hears of the displays it makes. Any thread, before the job starts. */
	public void onDisplay(DisplayListener listener) {
		this.onDisplay = listener;
	}

	/** What placing sounds and looks like; tests listen in. Any thread, before the job starts. */
	public void feel(PlacementFeel feel) {
		this.feel = feel;
	}

	/** Positions written so far, each counted once however often it's rewritten; a door's two halves count two. */
	public int placed() {
		return placed;
	}

	/** Completes, with {@link #placed()}, once everything queued is placed and nothing waits for a player to move. */
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
		felt = 0;
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
		if (machines != null) {
			if (powerUp == null) {
				takeOutRuleBreakers();
				powerUp = new PowerUp(level, machines, occupancy, powered, leverRestores, journal.positions());
			}
			if (!powerUp.run(deadline)) {
				return false;
			}
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
		for (Iterator<Held> it = waiting.iterator(); it.hasNext() && budget > 0;) {
			Held held = it.next();
			if (!occupancy.occupied(held.pos())) {
				it.remove();
				placeBlock(held.pos(), held.state());
				if (held.data() != null) {
					dress(held.pos(), held.data());
				}
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

	/**
	 * Writes one placement, both halves of a two-block block, then its block entity's data; or hangs an item frame; or
	 * shows a moving part's block. Returns how much of the tick's budget it used.
	 */
	private int place(Placement placement) {
		try {
			BlockPos pos = Positions.toBlockPos(placement.pos());
			if (!placement.part().isEmpty()) {
				show(placement, pos);
				return 1;
			}
			if (placement.block() == null) {
				if (placement.data() instanceof BlockEntityData.ItemFrame frame) {
					hang(pos, frame);
				}
				return 1;
			}
			if (placement.onlyReplacing() != null && !matches(level.getBlockState(pos), placement.onlyReplacing())) {
				return 0;
			}
			int written = 0;
			for (Map.Entry<BlockPos, BlockState> entry : StateFactory.states(placement.block(), rotation, pos)) {
				BlockEntityData data = entry.getKey().equals(pos) ? placement.data() : null;
				if (occupancy.occupied(entry.getKey())) {
					waiting.add(new Held(entry.getKey(), entry.getValue(), data));
					continue;
				}
				if (placeBlock(entry.getKey(), entry.getValue())) {
					written++;
				}
				if (data != null) {
					dress(entry.getKey(), data);
				}
			}
			return Math.max(written, 1);
		} catch (RuntimeException e) {
			if (failures++ == 0) {
				AiBuilderMod.LOG.warn("Couldn't place {} at {}", placement.block() == null ? "an item frame" : placement.block().info().id(),
						placement.pos(), e);
			}
			return 1;
		}
	}

	/**
	 * Gives the block entity just placed its data. A spot the build hadn't changed (the same block was already there)
	 * is journaled first: what it held is the world's, and undo gives it back.
	 */
	private void dress(BlockPos pos, BlockEntityData data) {
		BlockEntity entity = level.getBlockEntity(pos);
		if (entity == null) {
			// The block didn't go in: the build is at its cap.
			return;
		}
		Pos at = Positions.toPos(pos);
		if (!journal.has(at)) {
			if (journal.size() >= maxChanged) {
				capReached = true;
				overCap++;
				return;
			}
			journal.beforeWrite(at, level.getBlockState(pos), entity.saveWithFullMetadata(level.registryAccess()));
			placed++;
		}
		BlockEntityWriter.write(level, pos, data);
		journal.afterWrite(at, id(level.getBlockState(pos).getBlock()), BlockEntityHash.of(level.getBlockEntity(pos), level.registryAccess()));
		settling.add(new Settling(pos, level.getGameTime() + Tunables.BLOCK_ENTITY_SETTLE_TICKS));
	}

	/**
	 * A moving part's block (spec v2 §4.1): a display at the block's corner with the state the block would have, so
	 * facings turn as blocks do, journaled by its id. No block changes and nothing waits for a player (a display has
	 * no collision). A spot the part already shows takes the later block, as a block would.
	 */
	private void show(Placement placement, BlockPos pos) {
		Map<BlockPos, UUID> part = shown.computeIfAbsent(placement.part(), p -> new HashMap<>());
		for (Map.Entry<BlockPos, BlockState> entry : StateFactory.states(placement.block(), rotation, pos)) {
			UUID shown = part.get(entry.getKey());
			BlockState before = shown == null ? null : Displays.show(level, shown, entry.getValue());
			if (before != null && !made.contains(shown) && before != entry.getValue()) {
				journal.reshown(shown, Positions.toPos(entry.getKey()), before);
			}
			if (before == null) {
				if (entry.getValue().isAir()) {
					// Nothing of the part's here to take out, and a display of air would be nothing to see.
					continue;
				}
				Display.BlockDisplay display = Displays.spawn(level, entry.getKey(), entry.getValue());
				if (display == null) {
					if (failures++ == 0) {
						AiBuilderMod.LOG.warn("Couldn't show {} at {} in a moving part", placement.block().info().id(), entry.getKey());
					}
					continue;
				}
				part.put(entry.getKey(), display.getUUID());
				made.add(display.getUUID());
				journal.addSpawned(new UndoJournal.Spawned(display.getUUID(), Positions.toPos(entry.getKey()), "", UndoJournal.Spawned.DISPLAY));
				onDisplay.shown(placement.part(), display.getUUID(), entry.getKey());
			}
			if (felt < Tunables.FEEL_PER_TICK && !entry.getValue().isAir()) {
				felt++;
				feel.placed(level, entry.getKey(), entry.getValue());
			}
		}
	}

	/** An item frame (spec §10): journaled by its id, so undo takes it down before the blocks it hangs on. */
	private void hang(BlockPos pos, BlockEntityData.ItemFrame data) {
		Direction facing = rotation.rotate(Direction.byName(data.facing()));
		ItemFrame frame = ItemFrames.hang(level, pos, facing, data.item(), data.glowing());
		if (frame == null) {
			if (failures++ == 0) {
				AiBuilderMod.LOG.warn("Couldn't hang an item frame at {}: nothing solid behind it, or another frame there", pos);
			}
			return;
		}
		journal.addSpawned(new UndoJournal.Spawned(frame.getUUID(), Positions.toPos(pos), data.item(), UndoJournal.Spawned.FRAME));
	}

	/**
	 * One block of a placement; a position the build hadn't changed before adds one to {@link #placed()}. The first
	 * few blocks of a tick get the feel (not the finalize pass, which writes through {@link #write} alone).
	 */
	private boolean placeBlock(BlockPos pos, BlockState state) {
		int known = journal.size();
		boolean wrote = write(pos, state);
		if (wrote) {
			// What's written now is started afresh, whatever was here before (M8 review).
			powered.remove(pos);
		}
		if (journal.size() > known) {
			placed++;
		}
		if (wrote && !state.isAir() && felt < Tunables.FEEL_PER_TICK) {
			felt++;
			feel.placed(level, pos, state);
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
		if (now.getBlock() instanceof LeverBlock) {
			journal.lever(at, now.getValue(LeverBlock.POWERED));
		}
		if (journal.size() >= maxChanged) {
			capReached = true;
		}
		if (after != null) {
			settling.add(new Settling(pos, level.getGameTime() + Tunables.BLOCK_ENTITY_SETTLE_TICKS));
		}
		return true;
	}

	/** What would move or power something that isn't the build's, put back as it was before the build, as undo would. */
	private void takeOutRuleBreakers() {
		for (MachineRules.Breaker breaker : machines.breakers()) {
			Pos at = Positions.toPos(breaker.pos());
			UndoRunner.putBack(level, breaker.pos(), journal.original(at), journal.originalData(at));
			takenOut.add(breaker.pos());
			AiBuilderMod.LOG.info("Took out {} at {}: {}", breaker.kind(), breaker.pos(), breaker.detail());
		}
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
