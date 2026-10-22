package io.palmisano.aibuilder.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/** Positions that mustn't get a block right now. */
public interface Occupancy {
	boolean occupied(BlockPos pos);

	/** Where any player's hitbox is: a block there would trap them (spec §5.5). */
	static Occupancy players(ServerLevel level) {
		return pos -> !level.getEntitiesOfClass(Player.class, new AABB(pos)).isEmpty();
	}
}
