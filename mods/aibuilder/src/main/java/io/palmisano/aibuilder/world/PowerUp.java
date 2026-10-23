package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.palmisano.aibuilder.AiBuilderMod;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Pos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;

/**
 * The power-up pass (spec v2 §5.1): a build's redstone starts as if it had been placed by hand. Each position the
 * build changed that holds a redstone component now, in the order first changed, gets what placing it would have
 * done: its {@code onPlace} and a look at what powers it, and, for what gives power by itself, a neighbour update (a
 * lever's also round the block it's on, as flipping it does). A door, a lamp or a button tells its neighbours nothing
 * (M8 review). Once each: the components started are kept, so a later pass never repeats one (a clock powered twice
 * would carry two pulses), and a tool's write to a position takes it off the record. Before it starts, a player where
 * one of the pistons of the build (or of the player's earlier builds here) would push holds it back, for at most
 * {@link Tunables#MACHINE_PLAYER_WAIT_TICKS} (spec v2 §5.5); then it gives up and the machine stays as it was. Once the
 * way is clear, the levers a test had to leave flipped go back first. On the tick budget, at least one position a tick.
 * Server thread.
 */
final class PowerUp {
	private final ServerLevel level;
	private final Occupancy occupancy;
	/** Components started already, this pass's added as it goes. */
	private final Set<BlockPos> powered;
	/** Levers a test couldn't put back (a player stood in the way), with how they should be. */
	private final Map<BlockPos, Boolean> putBack;
	private final List<BlockPos> pistons;
	private final List<BlockPos> todo = new ArrayList<>();
	private final long giveUpAt;
	private int next;
	private boolean clear;
	private BlockPos waitingFor;
	private boolean gaveUp;
	private boolean loggedFailure;
	private int started;

	PowerUp(ServerLevel level, MachineRules rules, Occupancy occupancy, Set<BlockPos> powered, Map<BlockPos, Boolean> putBack, List<Pos> positions) {
		this.level = level;
		this.occupancy = occupancy;
		this.powered = powered;
		this.putBack = putBack;
		this.pistons = rules.pistons();
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
		if (!clear) {
			waitingFor = MachineRules.playerInReach(level, pistons, occupancy);
			if (waitingFor != null) {
				if (level.getGameTime() < giveUpAt) {
					return false;
				}
				gaveUp = true;
				waitingFor = null;
				return true;
			}
			clear = true;
			leversBack();
		}
		do {
			if (next < todo.size()) {
				power(todo.get(next++));
			}
		} while (next < todo.size() && System.nanoTime() < deadline);
		return next >= todo.size();
	}

	/** A piston of the build's that would push where a player stands, while the pass waits; null when none would. */
	BlockPos waitingFor() {
		return waitingFor;
	}

	/** How many redstone components it has started. */
	int started() {
		return started;
	}

	/** It waited for a player as long as it may and didn't start the machine. */
	boolean gaveUp() {
		return gaveUp;
	}

	private void leversBack() {
		putBack.forEach((pos, on) -> {
			BlockState state = level.getBlockState(pos);
			if (level.isLoaded(pos) && state.getBlock() instanceof LeverBlock lever && state.getValue(LeverBlock.POWERED) != on) {
				lever.pull(state, level, pos, null);
			}
		});
		putBack.clear();
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
		if (!level.isLoaded(pos)) {
			return;
		}
		try {
			BlockState state = level.getBlockState(pos);
			if (!Redstone.component(state)) {
				return;
			}
			powered.add(pos);
			started++;
			state.onPlace(level, pos, Blocks.AIR.defaultBlockState(), false);
			// It looks at what powers it, as a change beside it would make it (a comparator reads its chest, a lamp its lever),
			// without telling its neighbours anything.
			level.neighborChanged(pos, Blocks.AIR, ExperimentalRedstoneUtils.initialOrientation(level, null, null));
			if (Redstone.emits(state)) {
				level.updateNeighborsAt(pos, state.getBlock(), ExperimentalRedstoneUtils.initialOrientation(level, null, null));
				if (state.getBlock() instanceof FaceAttachedHorizontalDirectionalBlock) {
					level.updateNeighborsAt(support(pos, state), state.getBlock(), ExperimentalRedstoneUtils.initialOrientation(level, null, null));
				}
			}
		} catch (RuntimeException e) {
			if (!loggedFailure) {
				loggedFailure = true;
				AiBuilderMod.LOG.warn("Couldn't power up the redstone at {}", pos, e);
			}
		}
	}
}
