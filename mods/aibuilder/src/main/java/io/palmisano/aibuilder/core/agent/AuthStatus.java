package io.palmisano.aibuilder.core.agent;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonElement;
import io.palmisano.aibuilder.core.Tunables;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * What {@code claude auth status --json} said. Builds run on a subscription login only (claude.ai, or a long-lived
 * {@code oauth_token} from {@code claude setup-token}); the mod reads nothing else about the account. {@code answered}:
 * it printed JSON saying whether it's logged in; otherwise Claude Code didn't run properly, and logging in won't help.
 */
public record AuthStatus(boolean loggedIn, String method, boolean answered) {
	private static final Set<String> SUBSCRIPTION = Set.of("claude.ai", "oauth_token");

	/** Never throws: anything unreadable is no answer. A run that failed isn't logged in whatever it printed. */
	public static AuthStatus parse(int exitCode, String stdout) {
		boolean answered = false;
		boolean loggedIn = false;
		String method = "";
		try {
			JsonElement root = JsonParser.parseString(stdout == null ? "" : stdout);
			if (root.isJsonObject()) {
				JsonObject o = root.getAsJsonObject();
				answered = o.get("loggedIn") instanceof JsonPrimitive p && p.isBoolean();
				loggedIn = answered && o.get("loggedIn").getAsBoolean();
				method = o.get("authMethod") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : "";
			}
		} catch (RuntimeException e) {
			// Not JSON: an old or broken Claude Code.
		}
		return new AuthStatus(exitCode == 0 && loggedIn, method, answered);
	}

	/**
	 * For the player, why a Claude Code that gave no answer didn't run: the first line of {@code stderr} that names an
	 * error (Node's stack and version banner around it say nothing), else its last line, else the exit code.
	 */
	public static String why(String stderr, int exitCode) {
		List<String> lines = (stderr == null ? "" : stderr).lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
		String line = lines.stream().filter(l -> l.toLowerCase(Locale.ROOT).contains("error")).findFirst()
				.orElse(lines.isEmpty() ? "" : lines.getLast());
		if (line.isEmpty()) {
			return exitCode == 0 ? "no answer to claude auth status" : "exit code " + exitCode;
		}
		return line.length() <= Tunables.CLAUDE_ERROR_MAX ? line : line.substring(0, Tunables.CLAUDE_ERROR_MAX - 1) + "…";
	}

	/** Logged in with a subscription. */
	public boolean ok() {
		return loggedIn && SUBSCRIPTION.contains(method);
	}
}
