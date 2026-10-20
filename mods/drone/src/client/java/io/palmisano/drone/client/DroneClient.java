package io.palmisano.drone.client;

import io.palmisano.drone.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;

public class DroneClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModelLayerRegistry.registerModelLayer(DroneModel.LAYER, DroneModel::createLayer);
		EntityRendererRegistry.register(ModEntities.DRONE, DroneRenderer::new);
	}
}
