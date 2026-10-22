package io.palmisano.aibuilder;

import com.mojang.authlib.GameProfile;
import io.palmisano.aibuilder.mixin.CommandSourceStackAccessor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who may build (spec §15). Builds run on the world owner's own Claude login, so only the singleplayer owner may
 * start one, by UUID (an offline LAN guest can take the host's name), and only by their own hand; M6 adds the
 * dedicated-server allow list.
 */
public final class BuildPermission {
	private BuildPermission() {
	}

	public static boolean canBuild(ServerPlayer player) {
		GameProfile owner = player.level().getServer().getSingleplayerProfile();
		return owner != null && player.getUUID().equals(owner.id());
	}

	/**
	 * The player typed this command: not a command block's {@code /execute as <player>}. In 26.3 a player's commands
	 * carry the player's own {@code commandSource()}, an object of its own rather than the player.
	 */
	public static boolean ownCommand(CommandSourceStack source, ServerPlayer player) {
		return ((CommandSourceStackAccessor) source).aibuilder$source() == player.commandSource();
	}
}
