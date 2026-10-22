package io.palmisano.aibuilder.world;

import java.util.List;

import io.palmisano.aibuilder.AiBuilderMod;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The finalize pass (spec §9): every changed block, and every block next to one, gets the shape its neighbours give
 * it ({@link Block#updateFromNeighbourShapes}, as structure placement does), so fences, panes, walls and stairs
 * connect, and a block left without support goes the way it would for a player. Anything holding a fluid,
 * waterlogged blocks included, is left alone: a shape update would start it flowing. Spread over ticks like the
 * placement itself, since a big build has hundreds of thousands of positions.
 */
final class ShapePass {
	interface Writer {
		void write(BlockPos pos, BlockState state);
	}

	private static final Direction[] SIDES = Direction.values();

	private final ServerLevel level;
	private final List<BlockPos> changed;
	private final Writer writer;
	private final LongOpenHashSet done = new LongOpenHashSet();
	private int index;
	/** -1 for the changed position itself, then each side in turn. */
	private int side = -1;
	private boolean loggedFailure;

	ShapePass(ServerLevel level, List<BlockPos> changed, Writer writer) {
		this.level = level;
		this.changed = changed;
		this.writer = writer;
	}

	/** Works until {@code deadline} (a {@link System#nanoTime()}); true once every position has had its update. */
	boolean run(long deadline) {
		while (index < changed.size()) {
			if (System.nanoTime() >= deadline) {
				return false;
			}
			BlockPos base = changed.get(index);
			BlockPos pos = side < 0 ? base : base.relative(SIDES[side]);
			if (++side == SIDES.length) {
				side = -1;
				index++;
			}
			if (done.add(pos.asLong())) {
				reshape(pos);
			}
		}
		return true;
	}

	private void reshape(BlockPos pos) {
		try {
			BlockState state = level.getBlockState(pos);
			if (!state.getFluidState().isEmpty()) {
				return;
			}
			BlockState shaped = Block.updateFromNeighbourShapes(state, level, pos);
			if (shaped != state) {
				writer.write(pos, shaped);
			}
		} catch (RuntimeException e) {
			if (!loggedFailure) {
				loggedFailure = true;
				AiBuilderMod.LOG.warn("Couldn't update the shape at {}", pos, e);
			}
		}
	}
}
