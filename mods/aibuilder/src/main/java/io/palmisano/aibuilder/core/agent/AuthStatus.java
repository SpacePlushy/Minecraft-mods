package io.palmisano.aibuilder.core.agent;

import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * What {@code claude auth status --json} said. Builds run on a subscription login only (claude.ai, or a long-lived
 * {@code oauth_token} from {@code claude setup-token}); the mod reads nothing else about the account.
 */
public record AuthStatus(boolean loggedIn, String method) {
	private static final Set<String> SUBSCRIPTION = Set.of("claude.ai", "oauth_token");

	/** Never throws: anything unreadable counts as not logged in. A run that failed isn't ok whatever it printed. */
	public static AuthStatus parse(int exitCode, String stdout) {
		boolean loggedIn = false;
		String method = "";
		try {
			JsonElement root = JsonParser.parseString(stdout == null ? "" : stdout);
			if (root.isJsonObject()) {
				JsonObject o = root.getAsJsonObject();
				loggedIn = o.get("loggedIn") instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
				method = o.get("authMethod") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : "";
			}
		} catch (RuntimeException e) {
			// Not JSON: an old or broken Claude Code.
		}
		return new AuthStatus(exitCode == 0 && loggedIn, method);
	}

	/** Logged in with a subscription. */
	public boolean ok() {
		return loggedIn && SUBSCRIPTION.contains(method);
	}
}
