package io.palmisano.drone.registry;

import io.palmisano.drone.DroneMod;
import io.palmisano.drone.entity.DroneEntity;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	private static final ResourceKey<EntityType<?>> DRONE_KEY = ResourceKey.create(Registries.ENTITY_TYPE, DroneMod.id("drone"));

	public static final EntityType<DroneEntity> DRONE = Registry.register(BuiltInRegistries.ENTITY_TYPE, DRONE_KEY,
			EntityType.Builder.<DroneEntity>of(DroneEntity::new, MobCategory.MISC)
					.sized(1.5f, 0.5f)
					.passengerAttachments(0.5f)
					.clientTrackingRange(10)
					.build(DRONE_KEY));

	private ModEntities() {
	}

	/** Forces class loading so the static registrations run during mod init. */
	public static void init() {
	}
}
