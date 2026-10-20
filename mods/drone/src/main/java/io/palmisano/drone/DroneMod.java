package io.palmisano.drone;

import io.palmisano.drone.registry.ModEntities;
import io.palmisano.drone.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTabs;

public class DroneMod implements ModInitializer {
	public static final String MOD_ID = "drone";

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		ModItems.init();
		ModEntities.init();
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
				.register(output -> output.accept(ModItems.DRONE));
	}
}
