package io.palmisano.aibuilder;

import net.minecraft.server.level.ServerPlayer;

/**
 * Who may build (spec §15). Builds run on the world owner's own Claude login, so only the singleplayer owner
 * may start one; M6 adds the dedicated-server allow list.
 */
public final class BuildPermission {
	private BuildPermission() {
	}

	public static boolean canBuild(ServerPlayer player) {
		return player.level().getServer().isSingleplayerOwner(player.nameAndId());
	}
}
