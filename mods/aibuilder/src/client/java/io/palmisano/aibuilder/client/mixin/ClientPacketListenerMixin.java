package io.palmisano.aibuilder.client.mixin;

import io.palmisano.aibuilder.core.request.ClickedCommands;
import io.palmisano.aibuilder.core.request.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clicked commands (a book page, a sign, a tellraw link) reach the server as if typed, and 26.3 sends one that needs
 * no permission without asking. For {@code /aibuild} that would send someone else's prompt through the owner's Claude
 * login, so a click may only press the mod's own buttons (M4 review).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "sendUnattendedCommand", at = @At("HEAD"), cancellable = true)
	private void aibuilder$onlyTypedPrompts(String command, Screen screen, CallbackInfo ci) {
		if (ClickedCommands.refused(command)) {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.player != null) {
				minecraft.player.sendSystemMessage(Component.literal(Messages.clickedPrompt()));
			}
			ci.cancel();
		}
	}
}
