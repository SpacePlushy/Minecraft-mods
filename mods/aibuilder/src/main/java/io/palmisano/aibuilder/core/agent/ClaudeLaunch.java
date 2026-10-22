package io.palmisano.aibuilder.core.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;

/**
 * How Claude Code is started for a build (spec §12.2): headless, the build tools as its only tools, our MCP server as
 * its only server, none of the user's settings, hooks, plugins, CLAUDE.md files or memory, the prompt on stdin.
 */
public final class ClaudeLaunch {
	/** MCP tool calls may take this long; a checkpoint answers well before it. */
	public static final int MCP_TIMEOUT_MILLIS = 120_000;
	private static final Map<String, String> ADDED = Map.of("CLAUDE_CODE_DISABLE_CLAUDE_MDS", "1", "CLAUDE_CODE_DISABLE_AUTO_MEMORY", "1",
			"MCP_DISCOVERY_CACHE", "0", "CLAUDE_CODE_STARTUP_FAILURE_RESULTS", "1", "DISABLE_AUTOUPDATER", "1");

	private ClaudeLaunch() {
	}

	/** {@code command} (the binary, or a test's fake), then every flag; never {@code --bare} (it skips the login). */
	public static List<String> arguments(List<String> command, Path mcpConfig, Path systemPrompt, ClaudeSettings settings, UUID session) {
		List<String> args = new ArrayList<>(command);
		args.addAll(List.of("-p", "--verbose", "--output-format", "stream-json", "--include-partial-messages", "--strict-mcp-config", "--mcp-config",
				mcpConfig.toString(), "--tools", "", "--allowedTools", "mcp__" + InitCheck.SERVER + "__*", "--permission-mode", "dontAsk",
				"--permission-prompts", "none", "--restricted", "--disable-slash-commands", "--system-prompt-file", systemPrompt.toString(),
				"--model", settings.model(), "--effort", settings.effort(), "--max-turns", Integer.toString(settings.maxTurns()), "--session-id",
				session.toString()));
		return args;
	}

	/**
	 * The child's environment: the game's, without any API key or provider switch (in {@code -p} a key always wins over
	 * the login) and without another Claude Code session's variables (a game started from inside Claude Code carries
	 * dozens, and a base URL; the M4 spike), plus the switches that keep the user's own setup out.
	 * {@code CLAUDE_CONFIG_DIR} stays: the login can live there.
	 */
	public static Map<String, String> environment(Map<String, String> inherited) {
		Map<String, String> env = new LinkedHashMap<>();
		inherited.forEach((name, value) -> {
			String upper = name.toUpperCase(Locale.ROOT);
			boolean dropped = upper.startsWith("ANTHROPIC_") || (upper.startsWith("CLAUDE") && !upper.equals("CLAUDE_CONFIG_DIR"))
					|| ADDED.containsKey(upper);
			if (!dropped) {
				env.put(name, value);
			}
		});
		env.putAll(ADDED);
		return env;
	}

	/** {@code mcp.json}: our one server, over HTTP on loopback, with this build's token. */
	public static String mcpConfig(int port, String token) {
		JsonObject headers = new JsonObject();
		headers.addProperty("Authorization", "Bearer " + token);
		JsonObject server = new JsonObject();
		server.addProperty("type", "http");
		server.addProperty("url", "http://127.0.0.1:" + port + "/mcp");
		server.add("headers", headers);
		server.addProperty("timeout", MCP_TIMEOUT_MILLIS);
		JsonObject servers = new JsonObject();
		servers.add(InitCheck.SERVER, server);
		JsonObject root = new JsonObject();
		root.add("mcpServers", servers);
		return root.toString();
	}
}
