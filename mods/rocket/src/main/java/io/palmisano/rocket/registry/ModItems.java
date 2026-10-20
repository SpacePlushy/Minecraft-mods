package io.palmisano.rocket.registry;

import io.palmisano.rocket.RocketMod;
import io.palmisano.rocket.item.RocketItem;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

public final class ModItems {
	private static final ResourceKey<Item> ROCKET_KEY = ResourceKey.create(Registries.ITEM, RocketMod.id("rocket"));

	public static final Item ROCKET = Registry.register(BuiltInRegistries.ITEM, ROCKET_KEY,
			new RocketItem(new Item.Properties().setId(ROCKET_KEY).stacksTo(1)));

	private ModItems() {
	}

	/** Forces class loading so the static registrations run during mod init. */
	public static void init() {
	}
}
