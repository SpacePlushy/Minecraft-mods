package io.palmisano.aibuilder.core.agent;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The start check (spec §12.2): before any tool call counts, Claude Code must have started with the build tools
 * only, the mod's MCP server only, on the subscription login (no API key) and with no plugin but its own built-in
 * ones. Anything else means the launch flags didn't hold, so the build doesn't run.
 */
public final class InitCheck {
	public static final String SERVER = "aibuilder";
	private static final String PREFIX = "mcp__" + SERVER + "__";
	/** Tools Claude Code may keep beside MCP tools; none in 2.1.306. */
	private static final Set<String> ALSO_ALLOWED = Set.of("EndConversation");

	private InitCheck() {
	}

	/** What's wrong with this start, for the player ("an extra tool: Bash"); empty when it's fine. */
	public static Optional<String> problem(StreamEvent.Init init) {
		List<String> extra = init.tools().stream().filter(t -> !t.startsWith(PREFIX) && !ALSO_ALLOWED.contains(t)).toList();
		if (!extra.isEmpty()) {
			return Optional.of((extra.size() == 1 ? "an extra tool: " : "extra tools: ") + String.join(", ", extra));
		}
		if (init.tools().stream().noneMatch(t -> t.startsWith(PREFIX))) {
			return Optional.of("the build tools didn't load");
		}
		List<String> others = init.servers().stream().map(StreamEvent.Server::name).filter(name -> !name.equals(SERVER)).toList();
		if (!others.isEmpty()) {
			return Optional.of((others.size() == 1 ? "another MCP server: " : "other MCP servers: ") + String.join(", ", others));
		}
		Optional<StreamEvent.Server> ours = init.servers().stream().filter(s -> s.name().equals(SERVER)).findFirst();
		if (ours.isEmpty()) {
			return Optional.of("the " + SERVER + " server isn't connected");
		}
		if (!ours.get().status().equals("connected")) {
			return Optional.of("the " + SERVER + " server is " + (ours.get().status().isEmpty() ? "not connected" : ours.get().status()));
		}
		if (!init.apiKeySource().equals("none")) {
			return Optional.of("an API key (" + (init.apiKeySource().isEmpty() ? "from an unknown source" : init.apiKeySource())
					+ ") instead of your subscription login");
		}
		List<String> plugins = init.plugins().stream().filter(p -> !p.path().equals("builtin")).map(StreamEvent.Plugin::name).toList();
		if (!plugins.isEmpty()) {
			return Optional.of((plugins.size() == 1 ? "a plugin: " : "plugins: ") + String.join(", ", plugins));
		}
		return Optional.empty();
	}
}
