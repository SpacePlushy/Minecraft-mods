package io.palmisano.rocket.registry;

import io.palmisano.rocket.RocketMod;
import io.palmisano.rocket.entity.RocketEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.phys.Vec3;

public final class ModEntities {
	private static final ResourceKey<EntityType<?>> ROCKET_KEY = ResourceKey.create(Registries.ENTITY_TYPE, RocketMod.id("rocket"));

	/** 22 px square, 74 px tall; the rider sits on the cabin floor 2 blocks up, a little back so their legs fit. */
	public static final EntityType<RocketEntity> ROCKET = Registry.register(BuiltInRegistries.ENTITY_TYPE, ROCKET_KEY,
			EntityType.Builder.<RocketEntity>of(RocketEntity::new, MobCategory.MISC)
					.sized(1.375f, 4.625f)
					.passengerAttachments(new Vec3(0, 2.0, -0.25))
					.clientTrackingRange(10)
					.build(ROCKET_KEY));

	private ModEntities() {
	}

	/** Forces class loading so the static registrations run during mod init. */
	public static void init() {
	}
}
