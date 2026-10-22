package io.palmisano.aibuilder.client;

import java.util.stream.IntStream;

import com.mojang.blaze3d.platform.InputConstants;
import io.palmisano.aibuilder.AiBuilderMod;
import io.palmisano.aibuilder.net.BuildModePayload;
import io.palmisano.aibuilder.net.BuildStatusPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;

public class AiBuilderClient implements ClientModInitializer {
	/** Opens the builder screen; B by default, rebindable in Controls under "AI Builder". */
	public static final KeyMapping OPEN_BUILDER = new KeyMapping("key.aibuilder.open", InputConstants.KEY_B,
			KeyMapping.Category.register(AiBuilderMod.id("main")));

	@Override
	public void onInitializeClient() {
		KeyMappingHelper.registerKeyMapping(OPEN_BUILDER);
		// Fabric runs play payload handlers on the client thread.
		ClientPlayNetworking.registerGlobalReceiver(BuildStatusPayload.TYPE, (payload, context) -> StatusHud.show(payload.status()));
		ClientPlayNetworking.registerGlobalReceiver(BuildModePayload.TYPE, (payload, context) -> BuilderScreen.refining(payload.refining()));
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, AiBuilderMod.id("build_status"), new StatusHud());
		// Drain every click each tick, so a press while another screen is open never opens the builder later.
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (OPEN_BUILDER.consumeClick()) {
				if (mc.player != null && mc.gui.screen() == null && !shortcutHeld()) {
					mc.gui.setScreen(new BuilderScreen(PromptHistoryStore.standard()));
				}
			}
		});
	}

	/** Ctrl or Cmd is down (and isn't the builder's own key): B is the narrator's shortcut then (Ctrl+B; Cmd+B on macOS). */
	private static boolean shortcutHeld() {
		int bound = KeyMappingHelper.getBoundKeyOf(OPEN_BUILDER).getValue();
		return IntStream.of(InputConstants.KEY_LCONTROL, InputConstants.KEY_RCONTROL, InputConstants.KEY_LGUI, InputConstants.KEY_RGUI)
				.anyMatch(key -> key != bound && InputConstants.isKeyDown(key));
	}
}
