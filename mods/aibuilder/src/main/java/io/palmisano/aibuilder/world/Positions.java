package io.palmisano.aibuilder.world;

import io.palmisano.aibuilder.core.frame.Pos;
import net.minecraft.core.BlockPos;

/** Between the pure core's positions and Minecraft's. */
public final class Positions {
	private Positions() {
	}

	public static BlockPos toBlockPos(Pos pos) {
		return new BlockPos(pos.x(), pos.y(), pos.z());
	}

	public static Pos toPos(BlockPos pos) {
		return new Pos(pos.getX(), pos.getY(), pos.getZ());
	}
}
