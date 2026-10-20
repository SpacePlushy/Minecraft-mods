package io.palmisano.drone.item;

import io.palmisano.drone.entity.DroneEntity;
import io.palmisano.drone.registry.ModEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Places a drone on the clicked face, facing the player. Fails if there is no room. */
public class DroneItem extends Item {
	public DroneItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext ctx) {
		Level level = ctx.getLevel();
		if (!(level instanceof ServerLevel)) {
			return InteractionResult.SUCCESS;
		}
		DroneEntity drone = ModEntities.DRONE.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		if (drone == null) {
			return InteractionResult.FAIL;
		}
		Vec3 pos = Vec3.atBottomCenterOf(ctx.getClickedPos().relative(ctx.getClickedFace()));
		drone.snapTo(pos.x, pos.y, pos.z, ctx.getRotation(), 0);
		if (!level.noCollision(drone, drone.getBoundingBox())) {
			return InteractionResult.FAIL;
		}
		level.addFreshEntity(drone);
		ctx.getItemInHand().consume(1, ctx.getPlayer());
		return InteractionResult.SUCCESS;
	}
}
