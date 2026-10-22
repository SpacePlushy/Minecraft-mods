package io.palmisano.aibuilder;

import io.palmisano.aibuilder.core.request.BuildPrompt;
import io.palmisano.aibuilder.core.request.Messages;
import io.palmisano.aibuilder.core.request.SizeTier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** The one way a prompt enters the server, from the screen's packet or from /aibuild. Server thread only. */
public final class BuildManager {
	private BuildManager() {
	}

	public static void submit(ServerPlayer player, String rawPrompt, SizeTier size) {
		if (!BuildPermission.canBuild(player)) {
			reply(player, Messages.ownerOnly());
			return;
		}
		String prompt = BuildPrompt.normalize(rawPrompt);
		BuildPrompt.Problem problem = BuildPrompt.check(prompt);
		if (problem != BuildPrompt.Problem.NONE) {
			reply(player, Messages.problem(problem, prompt));
			return;
		}
		// M2 starts a build here instead of echoing.
		reply(player, Messages.received(prompt, size));
	}

	private static void reply(ServerPlayer player, String text) {
		player.sendSystemMessage(Component.literal(text));
	}
}
