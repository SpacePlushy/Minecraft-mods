package io.palmisano.aibuilder.world;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.site.Cell;
import io.palmisano.aibuilder.core.site.ColumnProbe;
import io.palmisano.aibuilder.core.site.GroundFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Where a new build goes (spec §5.4): in front of the player, on the floor nearest their feet. */
public final class Sites {
	private Sites() {
	}

	/** Facing the way the player looks, the origin {@link Tunables#SITE_GAP} blocks ahead of their feet. */
	public static LocalFrame ahead(ServerPlayer player) {
		Facing facing = Facing.fromYaw(player.getYRot());
		BlockPos feet = player.blockPosition();
		int x = feet.getX() + facing.forwardX() * Tunables.SITE_GAP;
		int z = feet.getZ() + facing.forwardZ() * Tunables.SITE_GAP;
		int y = GroundFinder.find(column(player.level(), x, z), feet.getY());
		return new LocalFrame(new Pos(x, y, z), facing);
	}

	/** The world's side of {@link GroundFinder}: water; open for air, plants, leaves, logs and snow layers; else ground. */
	static ColumnProbe column(ServerLevel level, int x, int z) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		return y -> {
			BlockState state = level.getBlockState(pos.set(x, y, z));
			if (state.getFluidState().is(FluidTags.WATER)) {
				return Cell.WATER;
			}
			if (state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.is(Blocks.SNOW)
					|| state.getCollisionShape(level, pos).isEmpty()) {
				return Cell.OPEN;
			}
			return Cell.GROUND;
		};
	}
}
