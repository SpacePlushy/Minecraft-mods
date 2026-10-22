package io.palmisano.aibuilder.core.request;

import java.util.Locale;
import java.util.UUID;

import io.palmisano.aibuilder.core.config.AiBuilderConfig;

/**
 * Who may build (spec §15). Builds run on one person's own Claude Code login, and Anthropic's terms describe a Pro
 * or Max plan as that person's own use. So in singleplayer, LAN included, only the world's owner builds, whatever the
 * config says; on a dedicated server, only players in {@code allowed_players} (by name, any case, or by UUID), with
 * {@code op_level} while {@code require_op}.
 */
public final class BuildRules {
	public enum Verdict {
		ALLOWED,
		/** Singleplayer or LAN, and not the world's owner. */
		OWNER_ONLY,
		/** A dedicated server whose allowed_players doesn't name them. */
		NOT_LISTED,
		/** Listed, but without the op level the config asks for. */
		NEEDS_OP
	}

	/** Where the player is: the integrated server of someone's singleplayer world, or a dedicated server. */
	public record Server(UUID owner) {
		public static final Server DEDICATED = new Server(null);

		public static Server integrated(UUID owner) {
			return new Server(owner);
		}
	}

	private BuildRules() {
	}

	public static Verdict decide(Server server, String name, UUID id, int permissionLevel, AiBuilderConfig config) {
		if (server.owner() != null) {
			return id.equals(server.owner()) ? Verdict.ALLOWED : Verdict.OWNER_ONLY;
		}
		boolean listed = config.allowedPlayers().stream()
				.anyMatch(entry -> entry.equalsIgnoreCase(id.toString()) || entry.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)));
		if (!listed) {
			return Verdict.NOT_LISTED;
		}
		return config.requireOp() && permissionLevel < config.opLevel() ? Verdict.NEEDS_OP : Verdict.ALLOWED;
	}
}
