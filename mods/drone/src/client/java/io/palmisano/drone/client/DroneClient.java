package io.palmisano.drone.client;

import io.palmisano.drone.entity.DroneEntity;
import io.palmisano.drone.flight.DroneInput;
import io.palmisano.drone.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.world.entity.player.Input;

public class DroneClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModelLayerRegistry.registerModelLayer(DroneModel.LAYER, DroneModel::createLayer);
		EntityRendererRegistry.register(ModEntities.DRONE, DroneRenderer::new);

		// Rider controls: movement keys tilt, Jump climbs, Sprint descends, mouse look steers.
		// Read through the player's key mappings so rebinding in Controls just works.
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player != null && mc.player.getVehicle() instanceof DroneEntity drone) {
				Input keys = mc.player.input.keyPresses;
				drone.setInput(DroneInput.fromKeys(keys.forward(), keys.backward(), keys.left(), keys.right(),
						keys.jump(), keys.sprint(), mc.player.getYRot()));
			}
		});
	}
}
