package io.palmisano.drone;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

public class DroneMod implements ModInitializer {
	public static final String MOD_ID = "drone";

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
	}
}
