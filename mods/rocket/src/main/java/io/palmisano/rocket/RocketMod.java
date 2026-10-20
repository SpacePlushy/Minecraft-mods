package io.palmisano.rocket;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

public class RocketMod implements ModInitializer {
	public static final String MOD_ID = "rocket";

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
	}
}
