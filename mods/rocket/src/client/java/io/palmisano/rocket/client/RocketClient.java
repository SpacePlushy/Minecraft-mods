package io.palmisano.rocket.client;

import io.palmisano.rocket.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;

public class RocketClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModelLayerRegistry.registerModelLayer(RocketModel.LAYER, RocketModel::createLayer);
		EntityRendererRegistry.register(ModEntities.ROCKET, RocketRenderer::new);
	}
}
