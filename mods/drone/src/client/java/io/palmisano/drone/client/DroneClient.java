package io.palmisano.drone.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.palmisano.drone.entity.DroneEntity;
import io.palmisano.drone.flight.DroneInput;
import io.palmisano.drone.registry.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
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
						keys.jump(), isHeld(mc, mc.options.keySprint), mc.player.getYRot()));
			}
		});
	}

	/**
	 * Whether the key bound to {@code mapping} is physically held. With Sprint set to Toggle, the
	 * mapping itself latches on a tap, which would make descend latch too.
	 */
	private static boolean isHeld(Minecraft mc, KeyMapping mapping) {
		if (mc.gui.screen() != null || mapping.isUnbound()) {
			return false;
		}
		InputConstants.Key key = KeyMappingHelper.getBoundKeyOf(mapping);
		return key.getType() == InputConstants.Type.KEYBOARD ? InputConstants.isKeyDown(key.getValue()) : mapping.isDown();
	}
}
