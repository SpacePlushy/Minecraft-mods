package io.palmisano.aibuilder;

import com.mojang.authlib.GameProfile;
import io.palmisano.aibuilder.core.config.AiBuilderConfig;
import io.palmisano.aibuilder.core.request.BuildRules;
import io.palmisano.aibuilder.core.request.Messages;
import io.palmisano.aibuilder.mixin.CommandSourceStackAccessor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who may build (spec §15), the world's side of {@link BuildRules}. Builds run on one person's own Claude login:
 * in singleplayer (LAN too) the world's owner, by UUID (an offline LAN guest can take the host's name); on a server
 * the players in allowed_players with the op level the config asks for. Only by their own hand, never a command block.
 */
public final class BuildPermission {
	private BuildPermission() {
	}

	public static BuildRules.Verdict verdict(ServerPlayer player, AiBuilderConfig config) {
		MinecraftServer server = player.level().getServer();
		GameProfile owner = server.getSingleplayerProfile();
		BuildRules.Server where = owner != null ? BuildRules.Server.integrated(owner.id()) : BuildRules.Server.DEDICATED;
		int level = server.getProfilePermissions(player.nameAndId()).level().id();
		return BuildRules.decide(where, player.getGameProfile().name(), player.getUUID(), level, config);
	}

	/** With the default config. */
	public static boolean canBuild(ServerPlayer player) {
		return verdict(player, AiBuilderConfig.DEFAULT) == BuildRules.Verdict.ALLOWED;
	}

	/** The player's line for a refusal. */
	public static String refusal(BuildRules.Verdict verdict, AiBuilderConfig config) {
		return switch (verdict) {
			case NOT_LISTED -> Messages.notListed();
			case NEEDS_OP -> Messages.needsOp(config.opLevel());
			default -> Messages.ownerOnly();
		};
	}

	/**
	 * The player typed this command: not a command block's {@code /execute as <player>}. In 26.3 a player's commands
	 * carry the player's own {@code commandSource()}, an object of its own rather than the player.
	 */
	public static boolean ownCommand(CommandSourceStack source, ServerPlayer player) {
		return ((CommandSourceStackAccessor) source).aibuilder$source() == player.commandSource();
	}
}
