package io.palmisano.rocket.item;

import io.palmisano.rocket.entity.RocketEntity;
import io.palmisano.rocket.registry.ModEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Stands a rocket on the clicked face, facing the way the player looks. Fails if there is no room. */
public class RocketItem extends Item {
	public RocketItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext ctx) {
		Level level = ctx.getLevel();
		if (!(level instanceof ServerLevel)) {
			return InteractionResult.SUCCESS;
		}
		RocketEntity rocket = ModEntities.ROCKET.create(level, EntitySpawnReason.SPAWN_ITEM_USE);
		if (rocket == null) {
			return InteractionResult.FAIL;
		}
		Vec3 pos = Vec3.atBottomCenterOf(ctx.getClickedPos().relative(ctx.getClickedFace()));
		rocket.snapTo(pos.x, pos.y, pos.z, ctx.getRotation(), 0);
		if (!level.noCollision(rocket, rocket.getBoundingBox())) {
			return InteractionResult.FAIL;
		}
		level.addFreshEntity(rocket);
		ctx.getItemInHand().consume(1, ctx.getPlayer());
		return InteractionResult.SUCCESS;
	}
}
