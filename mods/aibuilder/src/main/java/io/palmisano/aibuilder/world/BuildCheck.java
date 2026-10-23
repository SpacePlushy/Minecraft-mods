package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.survey.BuildProblems.Kind;
import io.palmisano.aibuilder.core.survey.BuildProblems.Problem;
import io.palmisano.aibuilder.core.survey.Darkness;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * check_build's look over a build (spec §8), on the server tick within a time budget (at least one position a
 * tick): every position the build changed, and the spots on and beside them, for blocks that can't stay, dark
 * walkable spots under a roof, doors that can't be walked through, headroom under 2 and fluid that could spill.
 * Positions outside the allowed box aren't reported (the build can't change them), and nothing in an unloaded chunk
 * is read (reading it would load it on the spot).
 */
public final class BuildCheck implements Tickable {
	private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
	/** How high a player steps up without jumping: a carpet, a bottom slab or a closed trapdoor is no higher. */
	private static final double STEP = Attributes.STEP_HEIGHT.value().getDefaultValue();
	/** How tall a player stands: on a bottom slab, 2.3 blocks of room. */
	private static final double PLAYER_HEIGHT = EntityTypes.PLAYER.getHeight();

	private final ServerLevel level;
	private final LocalFrame frame;
	private final Box allowed;
	private final UndoJournal<BlockState, CompoundTag> journal;
	private final long budgetNanos;
	/** What a machine may touch (spec v2 §5.5): its pistons and redstone, looked over with the rest. */
	private final MachineRules machines;
	private final CompletableFuture<List<Problem>> result = new CompletableFuture<>();
	private final List<Problem> problems = new ArrayList<>();
	/** Spots already looked at for darkness, as {@link BlockPos#asLong()}. */
	private final LongOpenHashSet seen = new LongOpenHashSet();
	private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
	private List<Pos> positions;
	private CompletableFuture<Void> light;
	private long lightDeadline;
	private int next;
	private int ticks;

	/** Server thread. {@code earlier}: the player's earlier builds here, whose blocks a machine may move as its own. */
	public BuildCheck(ServerLevel level, LocalFrame frame, Box allowed, UndoJournal<BlockState, CompoundTag> journal,
			List<UndoJournal<BlockState, CompoundTag>> earlier, long budgetNanos) {
		this.level = level;
		this.frame = frame;
		this.allowed = allowed;
		this.journal = journal;
		this.budgetNanos = budgetNanos;
		this.machines = new MachineRules(level, frame, allowed, journal, earlier);
	}

	/** What the power-up pass has started already: judged as placed, before it ran, so not again (M8 review). */
	public void started(Set<BlockPos> started) {
		machines.started(started);
	}

	/** Completes on the server thread with the problems, local positions. */
	public CompletableFuture<List<Problem>> result() {
		return result;
	}

	/** Ticks it has taken so far. */
	public int ticks() {
		return ticks;
	}

	@Override
	public boolean tick() {
		if (result.isDone()) {
			return true;
		}
		ticks++;
		if (positions == null) {
			// What the build changed, as it stands now: placement may go on while this reads (a call in the same turn).
			positions = journal.positions();
			light = lightCaughtUp();
			lightDeadline = System.nanoTime() + Tunables.CHECK_BUILD_LIGHT_WAIT_SECONDS * 1_000_000_000L;
		}
		// Block light is worked out on its own thread: a torch placed a moment ago may not have lit its room yet.
		if (!light.isDone() && System.nanoTime() < lightDeadline) {
			return false;
		}
		long deadline = System.nanoTime() + budgetNanos;
		do {
			if (next < positions.size()) {
				look(Positions.toBlockPos(positions.get(next++)));
			}
		} while (next < positions.size() && System.nanoTime() < deadline);
		if (next < positions.size()) {
			return false;
		}
		// What the build placed while this read (a retry joins a read a timeout left running): read too, its light first.
		if (journal.size() > positions.size()) {
			positions = journal.positions();
			light = lightCaughtUp();
			lightDeadline = System.nanoTime() + Tunables.CHECK_BUILD_LIGHT_WAIT_SECONDS * 1_000_000_000L;
			return false;
		}
		result.complete(List.copyOf(problems));
		return true;
	}

	@Override
	public void failed(RuntimeException error) {
		result.completeExceptionally(error);
	}

	/** Done once the light engine has worked through what was queued for the build's chunks before now. */
	private CompletableFuture<Void> lightCaughtUp() {
		Set<ChunkPos> chunks = BuildTickets.covering(Positions.toBlockPos(frame.toWorld(allowed.min())), Positions.toBlockPos(frame.toWorld(allowed.max())));
		List<CompletableFuture<?>> waits = new ArrayList<>();
		for (ChunkPos chunk : chunks) {
			waits.add(level.getChunkSource().getLightEngine().waitForPendingTasks(chunk.x(), chunk.z()));
		}
		return CompletableFuture.allOf(waits.toArray(CompletableFuture[]::new));
	}

	private void look(BlockPos pos) {
		BlockState state = loaded(pos);
		if (state == null) {
			return;
		}
		if (!state.isAir() && !(state.getBlock() instanceof LiquidBlock)) {
			unsupported(pos, state);
			door(pos, state);
			low(pos, state);
			MachineRules.Breaker breaker = machines.broken(pos, state);
			if (breaker != null) {
				report(breaker.kind(), pos, breaker.detail());
			}
		}
		spill(pos, state);
		dark(pos);
		dark(pos.above());
		for (Direction side : SIDES) {
			dark(pos.relative(side));
		}
	}

	/** It can't stay where it is (a torch on air), or it would fall (sand over a drop). */
	private void unsupported(BlockPos pos, BlockState state) {
		if (!loadedAround(pos)) {
			return;
		}
		boolean falls = state.getBlock() instanceof FallingBlock && FallingBlock.isFree(level.getBlockState(pos.below()));
		if (falls || !state.canSurvive(level, pos)) {
			report(Kind.UNSUPPORTED, pos, id(state));
		}
	}

	/**
	 * A door's lower half with something solid at head height on either side of it, at the feet higher than a step, or
	 * at the feet raising a player into the door's lintel or a block two above them (a bottom slab beside a door).
	 */
	private void door(BlockPos pos, BlockState state) {
		if (!(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) {
			return;
		}
		Direction facing = state.getValue(DoorBlock.FACING);
		for (Direction side : new Direction[] {facing, facing.getOpposite()}) {
			BlockPos feet = pos.relative(side);
			if (aboveAStep(feet) || solid(feet.above()) || top(feet) + PLAYER_HEIGHT > Math.min(room(feet.above(2)), room(pos.above(2)))) {
				report(Kind.DOOR, pos, "");
				return;
			}
		}
	}

	/** Under a solid block of the build, a walkable spot with only one block of air. Stairs and leaves aside. */
	private void low(BlockPos pos, BlockState state) {
		if (state.is(BlockTags.STAIRS) || state.is(BlockTags.LEAVES) || state.getCollisionShape(level, pos).isEmpty()) {
			return;
		}
		BlockPos spot = pos.below();
		if (free(spot) && floor(spot.below())) {
			report(Kind.LOW, spot, "");
		}
	}

	/** A source of water or lava next to an open side, or over air, would flow the moment anything disturbed it. */
	private void spill(BlockPos pos, BlockState state) {
		if (!(state.getBlock() instanceof LiquidBlock) || !state.getFluidState().isSource()) {
			return;
		}
		boolean open = free(pos.below());
		for (Direction side : SIDES) {
			open |= free(pos.relative(side));
		}
		if (open) {
			report(Kind.SPILL, pos, id(state));
		}
	}

	/** A walkable spot (2 blocks of air over a floor) under a roof, at block light 0: monsters spawn there. */
	private void dark(BlockPos spot) {
		// Free first: inside a solid build most spots aren't, and only free ones need remembering.
		if (!free(spot) || !seen.add(spot.asLong()) || !free(spot.above()) || !spawnGround(spot.below())) {
			return;
		}
		if (roofed(spot) && level.getBrightness(LightLayer.BLOCK, spot) == 0) {
			report(Kind.DARK, spot, "");
		}
	}

	/** Indoors ({@link Darkness}): little of the sky reaches it; without a sky, a roof close above it. */
	private boolean roofed(BlockPos spot) {
		return Darkness.indoors(level.dimensionType().hasSkyLight(), level.getBrightness(LightLayer.SKY, spot), up -> roof(spot.above(up)));
	}

	/** Something that keeps the weather off: anything solid or a fluid, leaves aside. */
	private boolean roof(BlockPos pos) {
		BlockState state = loaded(pos);
		return state != null && !state.is(BlockTags.LEAVES) && (!state.getFluidState().isEmpty() || !state.getCollisionShape(level, pos).isEmpty());
	}

	private void report(Kind kind, BlockPos pos, String detail) {
		Pos local = frame.toLocal(Positions.toPos(pos));
		if (allowed.contains(local)) {
			problems.add(new Problem(kind, local, detail));
		}
	}

	/** Nothing to bump into and no fluid: room for a player (or a monster). False where it isn't loaded. */
	private boolean free(BlockPos pos) {
		BlockState state = loaded(pos);
		return state != null && state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
	}

	private boolean solid(BlockPos pos) {
		BlockState state = loaded(pos);
		return state != null && !state.getCollisionShape(level, pos).isEmpty();
	}

	/** Solid higher than a step: walking into it stops a player. */
	private boolean aboveAStep(BlockPos pos) {
		return top(pos) > STEP;
	}

	/** How high a player stands on what's at {@code pos}, within its block: the top of its collision shape (0 for none). */
	private double top(BlockPos pos) {
		BlockState state = loaded(pos);
		VoxelShape shape = state == null ? null : state.getCollisionShape(level, pos);
		return shape == null || shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
	}

	/** The room under the block at {@code pos}, from the floor two below: 2 and the bottom of its collision shape (a top slab leaves 2.5). */
	private double room(BlockPos pos) {
		BlockState state = loaded(pos);
		VoxelShape shape = state == null ? null : state.getCollisionShape(level, pos);
		return shape == null || shape.isEmpty() ? Double.MAX_VALUE : 2 + shape.min(Direction.Axis.Y);
	}

	/** Ground a monster can spawn on, by vanilla's own rule (for a zombie): not glass, leaves or a bottom slab. */
	private boolean spawnGround(BlockPos pos) {
		BlockState state = loaded(pos);
		return state != null && state.isValidSpawn(level, pos, EntityTypes.ZOMBIE);
	}

	/** Something to stand on: a full top face (not a bottom slab, where nothing spawns). */
	private boolean floor(BlockPos pos) {
		BlockState state = loaded(pos);
		return state != null && state.isFaceSturdy(level, pos, Direction.UP);
	}

	/** The state there, or null when its chunk isn't loaded. */
	private BlockState loaded(BlockPos pos) {
		LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		return chunk == null ? null : chunk.getBlockState(pos);
	}

	/** canSurvive reads the neighbours: only where they're all loaded. (Up and down are in the same chunk.) */
	private boolean loadedAround(BlockPos pos) {
		for (Direction side : SIDES) {
			probe.setWithOffset(pos, side);
			if (level.getChunkSource().getChunkNow(probe.getX() >> 4, probe.getZ() >> 4) == null) {
				return false;
			}
		}
		return true;
	}

	private static String id(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}
}
