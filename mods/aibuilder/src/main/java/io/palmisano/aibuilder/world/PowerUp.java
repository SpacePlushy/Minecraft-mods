package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.palmisano.aibuilder.AiBuilderMod;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Pos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;

/**
 * The power-up pass (spec v2 §5.1): a build's redstone starts as if it had been placed by hand. Each position the
 * build changed that holds a redstone component now, in the order first changed, gets what placing it would have
 * done: its {@code onPlace}, then a neighbour update (a lever or button's also round the block it's on, as flipping it
 * does). Once each: the positions done are kept, so a later pass never repeats one (a clock powered twice would carry
 * two pulses). Nothing else gets an update, so sand and water away from the redstone stay as placed. Before it starts,
 * a player where one of the build's pistons would push holds it back, for at most
 * {@link Tunables#MACHINE_PLAYER_WAIT_TICKS} (spec v2 §5.5); then it gives up and the machine stays as it was placed.
 * On the tick budget, at least one position a tick. Server thread.
 */
final class PowerUp {
	private final ServerLevel level;
	private final MachineRules rules;
	private final Occupancy occupancy;
	/** Positions powered up already, this pass's added as it goes. */
	private final Set<BlockPos> powered;
	private final List<BlockPos> todo = new ArrayList<>();
	private final long giveUpAt;
	private int next;
	private BlockPos waitingFor;
	private boolean gaveUp;
	private boolean loggedFailure;

	PowerUp(ServerLevel level, MachineRules rules, Occupancy occupancy, Set<BlockPos> powered, List<Pos> positions) {
		this.level = level;
		this.rules = rules;
		this.occupancy = occupancy;
		this.powered = powered;
		for (Pos at : positions) {
			BlockPos pos = Positions.toBlockPos(at);
			if (!powered.contains(pos)) {
				todo.add(pos);
			}
		}
		this.giveUpAt = level.getGameTime() + Tunables.MACHINE_PLAYER_WAIT_TICKS;
	}

	/** Works until {@code deadline}; true once every position is done, or it has given up waiting for a player. */
	boolean run(long deadline) {
		if (gaveUp) {
			return true;
		}
		if (next == 0) {
			waitingFor = blockedPiston();
			if (waitingFor != null) {
				if (level.getGameTime() < giveUpAt) {
					return false;
				}
				gaveUp = true;
				waitingFor = null;
				return true;
			}
		}
		do {
			if (next < todo.size()) {
				power(todo.get(next++));
			}
		} while (next < todo.size() && System.nanoTime() < deadline);
		return next >= todo.size();
	}

	/** A piston of the build's, still to be powered, that would push where a player stands; null when none would. */
	BlockPos waitingFor() {
		return waitingFor;
	}

	/** It waited for a player as long as it may and didn't start the machine. */
	boolean gaveUp() {
		return gaveUp;
	}

	private BlockPos blockedPiston() {
		for (BlockPos pos : todo) {
			if (!level.isLoaded(pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof PistonBaseBlock && rules.reach(pos, state).stream().anyMatch(occupancy::occupied)) {
				return pos;
			}
		}
		return null;
	}

	/** The block a lever or button is on, which it powers as strongly as itself. */
	private static BlockPos support(BlockPos pos, BlockState state) {
		return switch (state.getValue(FaceAttachedHorizontalDirectionalBlock.FACE)) {
			case FLOOR -> pos.below();
			case CEILING -> pos.above();
			case WALL -> pos.relative(state.getValue(FaceAttachedHorizontalDirectionalBlock.FACING).getOpposite());
		};
	}

	private void power(BlockPos pos) {
		powered.add(pos);
		if (!level.isLoaded(pos)) {
			return;
		}
		try {
			BlockState state = level.getBlockState(pos);
			if (!Redstone.component(state)) {
				return;
			}
			state.onPlace(level, pos, Blocks.AIR.defaultBlockState(), false);
			level.updateNeighborsAt(pos, state.getBlock(), ExperimentalRedstoneUtils.initialOrientation(level, null, null));
			if (state.getBlock() instanceof FaceAttachedHorizontalDirectionalBlock) {
				level.updateNeighborsAt(support(pos, state), state.getBlock(), ExperimentalRedstoneUtils.initialOrientation(level, null, null));
			}
		} catch (RuntimeException e) {
			if (!loggedFailure) {
				loggedFailure = true;
				AiBuilderMod.LOG.warn("Couldn't power up the redstone at {}", pos, e);
			}
		}
	}
}
