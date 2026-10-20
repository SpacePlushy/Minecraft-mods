package io.palmisano.drone.registry;

import io.palmisano.drone.DroneMod;
import io.palmisano.drone.item.DroneItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

public final class ModItems {
	private static final ResourceKey<Item> DRONE_KEY = ResourceKey.create(Registries.ITEM, DroneMod.id("drone"));

	public static final Item DRONE = Registry.register(BuiltInRegistries.ITEM, DRONE_KEY,
			new DroneItem(new Item.Properties().setId(DRONE_KEY).stacksTo(1)));

	private ModItems() {
	}

	/** Forces class loading so the static registrations run during mod init. */
	public static void init() {
	}
}
