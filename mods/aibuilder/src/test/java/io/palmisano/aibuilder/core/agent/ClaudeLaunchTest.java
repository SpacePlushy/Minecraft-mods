package io.palmisano.aibuilder.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** How Claude Code is started (spec §12.2, with the M4 spike's environment rule). */
class ClaudeLaunchTest {
	private static final Path MCP = Path.of("/tmp/aibuilder/build-1/mcp.json");
	private static final Path PROMPT = Path.of("/tmp/aibuilder/build-1/builder_system.md");
	private static final UUID SESSION = UUID.fromString("ea63cf99-ab48-456c-8496-0097e064226d");

	private static List<String> launch(List<String> command) {
		return ClaudeLaunch.arguments(command, MCP, PROMPT, ClaudeSettings.DEFAULT, SESSION);
	}

	@Test
	void everyRequiredFlagIsThere() {
		assertEquals(List.of("/usr/local/bin/claude", "-p", "--verbose", "--output-format", "stream-json", "--include-partial-messages",
				"--strict-mcp-config", "--mcp-config", MCP.toString(), "--tools", "", "--allowedTools", "mcp__aibuilder__*", "--permission-mode",
				"dontAsk", "--permission-prompts", "none", "--restricted", "--disable-slash-commands", "--system-prompt-file", PROMPT.toString(),
				"--model", "claude-opus-5-5", "--effort", "high", "--max-turns", "200", "--session-id", SESSION.toString()),
				launch(List.of("/usr/local/bin/claude")));
	}

	@Test
	void aLongerCommandComesFirst() {
		List<String> args = launch(List.of("java", "FakeClaude.java"));
		assertEquals(List.of("java", "FakeClaude.java", "-p"), args.subList(0, 3));
	}

	@Test
	void neverBareOrNoPersistence() {
		List<String> args = launch(List.of("claude"));
		assertFalse(args.contains("--bare"), "--bare skips the subscription login");
		assertFalse(args.contains("--no-session-persistence"), "refinements resume the session");
	}

	/** A refinement (spec §12.2): --resume with the build's session, and every other flag again (resume restores none). */
	@Test
	void resumeUsesTheSession() {
		List<String> fresh = launch(List.of("claude"));
		List<String> resumed = ClaudeLaunch.arguments(List.of("claude"), MCP, PROMPT, ClaudeSettings.DEFAULT, SESSION, true);
		assertFalse(resumed.contains("--session-id"), resumed.toString());
		assertEquals(SESSION.toString(), resumed.get(resumed.indexOf("--resume") + 1));
		List<String> others = new java.util.ArrayList<>(fresh.subList(0, fresh.indexOf("--session-id")));
		assertEquals(others, resumed.subList(0, resumed.indexOf("--resume")), "every other flag, in the same order");
	}

	@Test
	void theSettingsAreUsed() {
		List<String> args = ClaudeLaunch.arguments(List.of("claude"), MCP, PROMPT, new ClaudeSettings("claude-sonnet-5-5", "medium", 90), SESSION);
		assertEquals("claude-sonnet-5-5", args.get(args.indexOf("--model") + 1));
		assertEquals("medium", args.get(args.indexOf("--effort") + 1));
		assertEquals("90", args.get(args.indexOf("--max-turns") + 1));
	}

	private static Map<String, String> inherited() {
		Map<String, String> env = new HashMap<>();
		env.put("ANTHROPIC_API_KEY", "sk-ant-api03-not-a-real-key");
		env.put("ANTHROPIC_AUTH_TOKEN", "token");
		env.put("ANTHROPIC_BASE_URL", "http://127.0.0.1:9/");
		env.put("anthropic_model", "lower case on Windows");
		env.put("CLAUDECODE", "1");
		env.put("CLAUDE_CODE_ENTRYPOINT", "claude-desktop");
		env.put("CLAUDE_CODE_SESSION_ID", "someone else's session");
		env.put("CLAUDE_CODE_USE_BEDROCK", "1");
		env.put("CLAUDE_CODE_USE_VERTEX", "1");
		env.put("CLAUDE_CODE_USE_FOUNDRY", "1");
		env.put("CLAUDE_PID", "123");
		env.put("PATH", "/usr/bin:/bin");
		env.put("HOME", "/Users/f");
		env.put("LANG", "en_US.UTF-8");
		env.put("CLAUDE_CONFIG_DIR", "/Users/f/.claude-work");
		env.put("DISABLE_AUTOUPDATER", "0");
		return env;
	}

	@Test
	void apiKeysAndHostSessionVariablesAreDropped() {
		Map<String, String> env = ClaudeLaunch.environment(inherited());
		for (String gone : List.of("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL", "anthropic_model", "CLAUDECODE",
				"CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_SESSION_ID", "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY",
				"CLAUDE_PID")) {
			assertFalse(env.containsKey(gone), gone + " reached Claude Code");
		}
		assertEquals("/usr/bin:/bin", env.get("PATH"));
		assertEquals("/Users/f", env.get("HOME"));
		assertEquals("en_US.UTF-8", env.get("LANG"));
		assertEquals("/Users/f/.claude-work", env.get("CLAUDE_CONFIG_DIR"), "the login can live there");
	}

	/**
	 * An npm install's claude runs "env node", with node beside it, but a game started from the launcher has a short
	 * PATH: the binary's folder goes first (once), under the PATH key the environment already uses.
	 */
	@Test
	void theBinarysFolderComesFirstOnPath() {
		Path claude = Path.of("/opt/homebrew/bin/claude");
		assertEquals("/opt/homebrew/bin:/usr/bin:/bin", ClaudeLaunch.binaryFirst(Map.of("PATH", "/usr/bin:/bin", "HOME", "/Users/f"), claude, ":")
				.get("PATH"));
		assertEquals("/opt/homebrew/bin:/usr/bin", ClaudeLaunch.binaryFirst(Map.of("PATH", "/opt/homebrew/bin:/usr/bin"), claude, ":").get("PATH"));
		Map<String, String> windows = ClaudeLaunch.binaryFirst(Map.of("Path", "/windows"), claude, ";");
		assertEquals("/opt/homebrew/bin;/windows", windows.get("Path"));
		assertFalse(windows.containsKey("PATH"));
		assertEquals("/opt/homebrew/bin", ClaudeLaunch.binaryFirst(Map.of(), claude, ":").get("PATH"));
		assertEquals(Map.of("PATH", "/usr/bin"), ClaudeLaunch.binaryFirst(Map.of("PATH", "/usr/bin"), Path.of("claude"), ":"), "no folder: as it was");
	}

	@Test
	void theFiveAreAdded() {
		Map<String, String> env = ClaudeLaunch.environment(inherited());
		for (String set : List.of("CLAUDE_CODE_DISABLE_CLAUDE_MDS", "CLAUDE_CODE_DISABLE_AUTO_MEMORY", "MCP_DISCOVERY_CACHE",
				"CLAUDE_CODE_STARTUP_FAILURE_RESULTS", "DISABLE_AUTOUPDATER")) {
			assertEquals(set.equals("MCP_DISCOVERY_CACHE") ? "0" : "1", env.get(set), set);
		}
	}

	@Test
	void mcpConfigCarriesTheTokenAndTimeout() {
		JsonObject server = JsonParser.parseString(ClaudeLaunch.mcpConfig(53476, "abc123")).getAsJsonObject().getAsJsonObject("mcpServers")
				.getAsJsonObject("aibuilder");
		assertEquals("http", server.get("type").getAsString());
		assertEquals("http://127.0.0.1:53476/mcp", server.get("url").getAsString());
		assertEquals("Bearer abc123", server.getAsJsonObject("headers").get("Authorization").getAsString());
		assertEquals(120000, server.get("timeout").getAsInt());
	}
}
