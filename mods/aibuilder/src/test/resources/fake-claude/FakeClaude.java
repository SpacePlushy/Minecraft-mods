// A fake `claude` for AI Builder's tests, run as `java FakeClaude.java <args>` (JDK only). It answers
// `auth status --json` and plays a `claude -p` run against the mod's MCP server, printing stream-json shaped like
// Claude Code 2.1.306's (the M4 spike's recordings are in ../stream/). The scenario comes from FAKE_CLAUDE_SCENARIO.

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FakeClaude {
	static final PrintStream OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
	static final HttpClient HTTP = HttpClient.newHttpClient();
	static String url;
	static String token;
	static String session = UUID.randomUUID().toString();
	static Path lock;
	/** Shaped like an Anthropic key, never a real one. */
	static final String FAKE_KEY = "sk-ant-api03-FAKEFAKEFAKEFAKEFAKEFAKE";
	static int nextId;
	static int turns;
	static volatile boolean done;

	public static void main(String[] args) throws Exception {
		List<String> a = List.of(args);
		if (a.size() == 2 && a.get(0).equals("--wait")) {
			// The orphan scenario's child: it says nothing, holds the output it was handed, and goes.
			Thread.sleep(Long.parseLong(a.get(1)) * 1000);
			return;
		}
		if (a.size() >= 2 && a.get(0).equals("auth") && a.get(1).equals("status")) {
			auth();
			return;
		}
		String given = after(a, "--session-id");
		if (given != null) {
			session = given;
		}
		// A refinement goes on in the build's session (spec §12.2).
		String resumed = after(a, "--resume");
		if (resumed != null) {
			session = resumed;
		}
		if (resumed != null && "1".equals(System.getenv("FAKE_CLAUDE_SESSION_GONE"))) {
			// What Claude Code says when the session's history isn't on this computer (any more).
			done = true;
			System.err.println("No conversation found with session ID: " + resumed);
			System.exit(1);
		}
		// Like Claude Code's session file: two processes on one session is the mod's bug (spec §12.2), so it's refused.
		lock = Path.of(System.getProperty("java.io.tmpdir"), "aibuilder-fake-claude-sessions", session);
		Files.createDirectories(lock.getParent());
		try {
			Files.createFile(lock);
		} catch (FileAlreadyExistsException e) {
			done = true;
			System.err.println("session " + session + " is in use by another process");
			System.exit(4);
		}
		lock.toFile().deleteOnExit();
		String prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
		String scenario = System.getenv().getOrDefault("FAKE_CLAUDE_SCENARIO", "build");
		// Ended by a signal (a cancel, a cap): finish the turn the way Claude Code does on SIGINT.
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (!done) {
				done = true;
				if (scenario.equals("slow_exit")) {
					// A process that takes its time to end after a cancel, within the mod's 5 s grace.
					try {
						Thread.sleep(4_000);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				OUT.println("{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,\"num_turns\":" + turns
						+ ",\"terminal_reason\":\"aborted_tools\",\"session_id\":\"" + session + "\"}");
				OUT.flush();
				// halt skips deleteOnExit.
				try {
					Files.deleteIfExists(lock);
				} catch (IOException e) {
					// a stale lock only refuses that session, which nothing resumes after a test run
				}
				Runtime.getRuntime().halt(0);
			}
		}));
		if (scenario.equals("no_init")) {
			Thread.sleep(60_000);
			return;
		}
		String config = Files.readString(Path.of(after(a, "--mcp-config")));
		url = group(config, "\"url\"\\s*:\\s*\"([^\"]+)\"");
		token = group(config, "Bearer ([0-9a-f]+)");
		List<String> tools = connect();
		String apiKeySource = scenario.equals("api_key") ? "ANTHROPIC_API_KEY" : "none";
		if (scenario.equals("extra_tool")) {
			tools.add("Bash");
		}
		String plugins = "{\"name\":\"cc-plugin-telemetry\",\"path\":\"builtin\",\"source\":\"cc-plugin-telemetry@builtin\"}"
				+ (scenario.equals("plugin") ? ",{\"name\":\"helper\",\"path\":\"/home/someone/.claude/plugins/helper\",\"source\":\"helper@market\"}" : "");
		OUT.println("{\"type\":\"system\",\"subtype\":\"init\",\"cwd\":\"/tmp/aibuilder/work\",\"session_id\":\"" + session + "\",\"tools\":["
				+ String.join(",", tools.stream().map(t -> quote(t)).toList()) + "],\"mcp_servers\":[{\"name\":\"aibuilder\",\"status\":\"connected\","
				+ "\"source\":\"dynamic\"}],\"model\":\"claude-opus-5-5\",\"permissionMode\":\"dontAsk\",\"slash_commands\":[],\"apiKeySource\":\""
				+ apiKeySource + "\",\"claude_code_version\":\"0.0.0-fake\",\"skills\":[],\"plugins\":[" + plugins + "],\"some_future_field\":{\"x\":1}}");
		switch (scenario) {
			case "build" -> {
				rateLimit("allowed", false);
				call("get_context", "{}");
				call("submit_brief", "{\"title\":\"Test hut\",\"style\":\"plain\",\"size\":{\"w\":3,\"h\":3,\"d\":3},"
						+ "\"palette\":[{\"role\":\"primary\",\"block\":\"oak_planks\"}],\"phases\":[\"walls\"]}");
				call("fill", "{\"box\":[-1,0,1,1,2,3],\"block\":\"oak_planks\",\"mode\":\"hollow\"}");
				call("checkpoint", "{\"phase\":\"walls\"}");
				call("finish", "{\"summary\":\"A tiny oak hut.\"}");
				result(false, "success", "Built a tiny oak hut.", "completed", 0);
			}
			case "echo" -> result(false, "success", prompt, "completed", 0);
			case "slow_build" -> {
				// The walls go in (stone, so they change what an earlier test hut left), then Claude takes its time: a build
				// the server stops half-way.
				call("get_context", "{}");
				call("submit_brief", "{\"title\":\"Test hut\",\"style\":\"plain\",\"size\":{\"w\":3,\"h\":3,\"d\":3},"
						+ "\"palette\":[{\"role\":\"primary\",\"block\":\"cobblestone\"}],\"phases\":[\"walls\",\"roof\"]}");
				call("fill", "{\"box\":[-1,0,1,1,2,3],\"block\":\"cobblestone\",\"mode\":\"hollow\"}");
				call("checkpoint", "{\"phase\":\"walls\"}");
				Thread.sleep(120_000);
				result(false, "success", "done", "completed", 0);
			}
			case "offline" -> {
				// The Wi-Fi goes off after the walls: Claude Code retries (no HTTP status: nothing answered), then gives up.
				call("get_context", "{}");
				call("submit_brief", "{\"title\":\"Test hut\",\"style\":\"plain\",\"size\":{\"w\":3,\"h\":3,\"d\":3},"
						+ "\"palette\":[{\"role\":\"primary\",\"block\":\"oak_planks\"}],\"phases\":[\"walls\",\"roof\"]}");
				call("fill", "{\"box\":[-1,0,1,1,2,3],\"block\":\"oak_planks\",\"mode\":\"hollow\"}");
				for (int attempt = 1; attempt <= 3; attempt++) {
					OUT.println("{\"type\":\"system\",\"subtype\":\"api_retry\",\"attempt\":" + attempt + ",\"max_retries\":3,\"retry_delay_ms\":"
							+ (300 * attempt) + ",\"error_status\":null,\"error\":\"unknown\",\"session_id\":\"" + session + "\"}");
					Thread.sleep(300L * attempt);
				}
				OUT.println("{\"type\":\"assistant\",\"message\":{\"id\":\"" + UUID.randomUUID() + "\",\"model\":\"<synthetic>\",\"role\":\"assistant\","
						+ "\"content\":[{\"type\":\"text\",\"text\":\"API Error: Connection error.\"}]},\"error\":\"unknown\",\"session_id\":\""
						+ session + "\"}");
				done = true;
				OUT.println("{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"num_turns\":" + turns + ",\"result\":"
						+ "\"API Error: Connection error.\",\"terminal_reason\":\"api_error\",\"api_error_status\":null,\"session_id\":\"" + session + "\"}");
				OUT.flush();
				System.exit(1);
			}
			case "leak" -> {
				// What must never reach a log (spec §16): the build's token, as a debug line might show it, and a key.
				String tokenFile = System.getenv("FAKE_CLAUDE_TOKEN_FILE");
				if (tokenFile != null) {
					Files.writeString(Path.of(tokenFile), token);
				}
				System.err.println("debug: MCP headers {Authorization: Bearer " + token + "}");
				OUT.println("{\"type\":\"system\",\"subtype\":\"debug\",\"text\":\"Authorization: Bearer " + token + "; ANTHROPIC_API_KEY=" + FAKE_KEY
						+ "\",\"session_id\":\"" + session + "\"}");
				call("get_context", "{}");
				call("finish", "{\"summary\":" + quote("Nothing built; the token was " + token + " and a key " + FAKE_KEY) + "}");
				result(false, "success", "Done with " + token, "completed", 0);
			}
			case "refine" -> {
				// What a refinement is given: the session it resumes and the box get_context now gives.
				String context = call("get_context", "{}");
				String box = context == null ? null : group(context, "the allowed box is (x -?\\d+\\.\\.-?\\d+, y -?\\d+\\.\\.-?\\d+, z -?\\d+\\.\\.-?\\d+)");
				call("finish", "{\"summary\":" + quote("Resumed " + resumed + "; the allowed box is " + box) + "}");
				result(false, "success", "Refined.", "completed", 0);
			}
			case "slow_exit" -> {
				call("get_context", "{}");
				call("submit_brief", "{\"title\":\"Test hut\",\"style\":\"plain\",\"size\":{\"w\":3,\"h\":3,\"d\":3},"
						+ "\"palette\":[{\"role\":\"primary\",\"block\":\"oak_planks\"}],\"phases\":[\"walls\"]}");
				Thread.sleep(60_000);
				result(false, "success", "done", "completed", 0);
			}
			case "extra_tool", "api_key", "plugin" -> {
				call("get_context", "{}");
				Thread.sleep(30_000);
				result(false, "success", "done", "completed", 0);
			}
			case "usage_limit", "overage" -> {
				rateLimit(scenario.equals("usage_limit") ? "rejected" : "allowed", scenario.equals("overage"));
				Thread.sleep(30_000);
				result(false, "success", "done", "completed", 0);
			}
			case "hang" -> Thread.sleep(60_000);
			case "orphan" -> {
				// A child that keeps the output open after this process has gone (a helper Claude Code left running):
				// this file again on the same Java, so it needs nothing from PATH (Windows has no `sleep`).
				String java = ProcessHandle.current().info().command().orElse("java");
				Path self = Path.of(FakeClaude.class.getProtectionDomain().getCodeSource().getLocation().toURI());
				new ProcessBuilder(java, self.toString(), "--wait", "4").redirectOutput(ProcessBuilder.Redirect.INHERIT)
						.redirectError(ProcessBuilder.Redirect.DISCARD).start();
				result(false, "success", "done", "completed", 0);
			}
			case "spam" -> {
				for (int i = 0; i < 400; i++) {
					call("get_context", "{}");
				}
				result(false, "success", "done", "completed", 0);
			}
			case "crash" -> {
				done = true;
				System.err.println("boom: the fake crashed");
				System.exit(3);
			}
			case "session_limit" -> {
				OUT.println("{\"type\":\"assistant\",\"message\":{\"id\":\"" + UUID.randomUUID() + "\",\"model\":\"<synthetic>\",\"role\":\"assistant\","
						+ "\"content\":[{\"type\":\"text\",\"text\":\"You've hit your session limit · resets 3pm\"}]},\"error\":\"rate_limit\","
						+ "\"session_id\":\"" + session + "\"}");
				result(true, "success", "You've hit your session limit · resets 3pm", "api_error", 1);
			}
			case "logged_out" -> {
				OUT.println("{\"type\":\"assistant\",\"message\":{\"id\":\"" + UUID.randomUUID() + "\",\"model\":\"<synthetic>\",\"role\":\"assistant\","
						+ "\"content\":[{\"type\":\"text\",\"text\":\"Not logged in · Please run /login\"}]},\"error\":\"authentication_failed\","
						+ "\"session_id\":\"" + session + "\"}");
				result(true, "success", "Not logged in · Please run /login", "api_error", 1);
			}
			default -> {
				System.err.println("unknown scenario " + scenario);
				done = true;
				System.exit(2);
			}
		}
	}

	static void auth() {
		done = true;
		if ("1".equals(System.getenv("FAKE_CLAUDE_BROKEN"))) {
			// A damaged install, as Node reports one.
			System.err.println("node:internal/modules/cjs/loader:1228\n  throw err;\n  ^\n\nError: Cannot find module"
					+ " '/usr/local/lib/node_modules/@anthropic-ai/claude-code/cli.js'\n    at Module._resolveFilename (node:internal/modules/cjs/loader:1225:15)"
					+ " {\n  code: 'MODULE_NOT_FOUND',\n  requireStack: []\n}\n\nNode.js v22.1.0");
			System.exit(1);
		}
		if ("1".equals(System.getenv("FAKE_CLAUDE_LOGGED_OUT"))) {
			OUT.println("{\n  \"loggedIn\": false,\n  \"authMethod\": \"none\",\n  \"apiProvider\": \"firstParty\"\n}");
			System.exit(1);
		}
		OUT.println("{\n  \"loggedIn\": true,\n  \"authMethod\": \"claude.ai\",\n  \"apiProvider\": \"firstParty\",\n  \"subscriptionType\": \"max\"\n}");
	}

	/** What Claude Code does before printing init: probe, initialize, list the tools. */
	static List<String> connect() throws Exception {
		post("{\"jsonrpc\":\"2.0\",\"id\":\"server-discover-probe-1\",\"method\":\"server/discover\",\"params\":{}}");
		post("{\"jsonrpc\":\"2.0\",\"id\":" + nextId++ + ",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
				+ "\"capabilities\":{},\"clientInfo\":{\"name\":\"fake-claude\",\"version\":\"0.0.0\"}}}");
		post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
		String listed = post("{\"jsonrpc\":\"2.0\",\"id\":" + nextId++ + ",\"method\":\"tools/list\"}");
		List<String> tools = new ArrayList<>();
		Matcher m = Pattern.compile("\\{\"name\":\"([a-z_]+)\",\"description\"").matcher(listed);
		while (m.find()) {
			tools.add("mcp__aibuilder__" + m.group(1));
		}
		return tools;
	}

	/** One model turn that calls one tool: the assistant line, the call over HTTP, the tool result line. Returns its text. */
	static String call(String tool, String arguments) throws Exception {
		turns++;
		String useId = "toolu_" + UUID.randomUUID().toString().replace("-", "");
		OUT.println("{\"type\":\"assistant\",\"message\":{\"id\":\"msg_" + UUID.randomUUID() + "\",\"model\":\"claude-opus-5-5\",\"role\":\"assistant\","
				+ "\"content\":[{\"type\":\"tool_use\",\"id\":\"" + useId + "\",\"name\":\"mcp__aibuilder__" + tool + "\",\"input\":" + arguments + "}],"
				+ "\"usage\":{\"input_tokens\":2,\"output_tokens\":1}},\"session_id\":\"" + session + "\"}");
		OUT.println("{\"type\":\"stream_event\",\"event\":{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"input_tokens\":3,\"cache_read_input_tokens\":1000,\"cache_creation_input_tokens\":50,\"output_tokens\":100}},"
				+ "\"session_id\":\"" + session + "\"}");
		String reply = post("{\"jsonrpc\":\"2.0\",\"id\":" + nextId++ + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":"
				+ arguments + ",\"_meta\":{\"claudecode/toolUseId\":\"" + useId + "\"}}}");
		String text = reply == null ? "unauthorized" : group(reply, "\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"");
		OUT.println("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"" + useId + "\",\"type\":\"tool_result\",\"content\":"
				+ "[{\"type\":\"text\",\"text\":\"" + (text == null ? "" : text) + "\"}]}]},\"session_id\":\"" + session + "\"}");
		return text;
	}

	static void rateLimit(String status, boolean overage) {
		OUT.println("{\"type\":\"rate_limit_event\",\"rate_limit_info\":{\"status\":\"" + status + "\",\"resetsAt\":"
				+ Instant.now().plusSeconds(3600).getEpochSecond() + ",\"rateLimitType\":\"five_hour\",\"overageStatus\":\"rejected\","
				+ "\"isUsingOverage\":" + overage + "},\"session_id\":\"" + session + "\"}");
	}

	static void result(boolean isError, String subtype, String text, String reason, int exitCode) {
		done = true;
		OUT.println("{\"type\":\"result\",\"subtype\":\"" + subtype + "\",\"is_error\":" + isError + ",\"num_turns\":" + Math.max(1, turns)
				+ ",\"result\":" + quote(text) + ",\"terminal_reason\":\"" + reason + "\",\"session_id\":\"" + session + "\",\"total_cost_usd\":0}");
		OUT.flush();
		System.exit(exitCode);
	}

	/** The reply's body, or null when refused (401). */
	static String post(String body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream").header("Authorization", "Bearer " + token)
				.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		return response.statusCode() == 401 ? null : response.body();
	}

	static String after(List<String> args, String flag) {
		int i = args.indexOf(flag);
		return i >= 0 && i + 1 < args.size() ? args.get(i + 1) : null;
	}

	static String group(String text, String regex) {
		Matcher m = Pattern.compile(regex).matcher(text);
		return m.find() ? m.group(1) : null;
	}

	static String quote(String s) {
		StringBuilder b = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> b.append("\\\"");
				case '\\' -> b.append("\\\\");
				case '\n' -> b.append("\\n");
				case '\r' -> b.append("\\r");
				case '\t' -> b.append("\\t");
				default -> {
					if (c < 0x20) {
						b.append(String.format("\\u%04x", (int) c));
					} else {
						b.append(c);
					}
				}
			}
		}
		return b.append('"').toString();
	}
}
