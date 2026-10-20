package io.palmisano.rocket.client;

import io.palmisano.rocket.entity.RocketEntity;
import io.palmisano.rocket.flight.RocketInput;
import io.palmisano.rocket.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.world.entity.player.Input;

public class RocketClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModelLayerRegistry.registerModelLayer(RocketModel.LAYER, RocketModel::createLayer);
		EntityRendererRegistry.register(ModEntities.ROCKET, RocketRenderer::new);

		// Rider controls: Jump ignites and throttles, movement keys tilt the nose, mouse look steers.
		// Read through the player's key mappings so rebinding in Controls just works.
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player != null && mc.player.getVehicle() instanceof RocketEntity rocket) {
				Input keys = mc.player.input.keyPresses;
				rocket.setInput(RocketInput.fromKeys(keys.forward(), keys.backward(), keys.left(), keys.right(),
						keys.jump(), mc.player.getYRot()));
			}
		});
	}
}
