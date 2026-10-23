package io.palmisano.aibuilder.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.palmisano.aibuilder.core.tools.ToolCatalog;
import io.palmisano.aibuilder.core.tools.ToolResult;
import org.junit.jupiter.api.Test;

/** The mod's MCP server, without the HTTP (spec §12.2, and what the M4 spike saw Claude Code send). */
class McpHandlerTest {
	private final McpSessions sessions = new McpSessions();
	private final List<String> calls = new CopyOnWriteArrayList<>();
	private volatile boolean ready = true;
	private final McpSession session = new McpSession() {
		@Override
		public boolean awaitReady(long millis) {
			return ready;
		}

		@Override
		public ToolResult call(String name, JsonObject arguments) {
			calls.add(name + " " + arguments);
			return name.equals("fill") ? new ToolResult("Queued 2 blocks.", false) : new ToolResult("There is no tool called \"" + name + "\".", true);
		}
	};
	private final McpHandler handler = new McpHandler(ToolCatalog.load().tools(), sessions);
	private final String token = sessions.open(session);

	private McpResponse post(String body) {
		return post(body, Map.of("authorization", "Bearer " + token));
	}

	private McpResponse post(String body, Map<String, String> headers) {
		return handler.handle(new McpRequest("POST", headers, body));
	}

	private static JsonObject json(McpResponse response) {
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	private static String initialize(String version) {
		return "{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"" + version
				+ "\",\"capabilities\":{\"roots\":{\"listChanged\":true}},\"clientInfo\":{\"name\":\"claude-code\",\"version\":\"2.1.306\"}}}";
	}

	private static String call(String name, String arguments) {
		return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":" + arguments
				+ ",\"_meta\":{\"claudecode/toolUseId\":\"toolu_1\",\"progressToken\":2}}}";
	}

	@Test
	void initializeNegotiatesTheVersion() {
		McpResponse response = post(initialize("2025-06-18"));
		assertEquals(200, response.status());
		JsonObject reply = json(response);
		assertEquals("2.0", reply.get("jsonrpc").getAsString());
		assertEquals(0, reply.get("id").getAsInt());
		JsonObject result = reply.getAsJsonObject("result");
		assertEquals("2025-06-18", result.get("protocolVersion").getAsString());
		assertFalse(result.getAsJsonObject("capabilities").getAsJsonObject("tools").get("listChanged").getAsBoolean());
		assertEquals("aibuilder", result.getAsJsonObject("serverInfo").get("name").getAsString());
		// What Claude Code 2.1.306 asks for, and a version this server doesn't know (it answers with its own).
		assertEquals("2025-11-25", json(post(initialize("2025-11-25"))).getAsJsonObject("result").get("protocolVersion").getAsString());
		assertEquals("2025-11-25", json(post(initialize("2027-01-01"))).getAsJsonObject("result").get("protocolVersion").getAsString());
	}

	/** Claude Code's first request: a newer method it falls back from when the server doesn't know it. */
	@Test
	void discoverProbeIsUnknown() {
		McpResponse response = post("{\"jsonrpc\":\"2.0\",\"id\":\"server-discover-probe-1\",\"method\":\"server/discover\",\"params\":{\"_meta\":"
				+ "{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}}");
		assertEquals(200, response.status());
		JsonObject reply = json(response);
		assertEquals("server-discover-probe-1", reply.get("id").getAsString());
		assertEquals(-32601, reply.getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	void notificationsGet202() {
		McpResponse response = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
		assertEquals(202, response.status());
		assertNull(response.body());
	}

	@Test
	void toolsListHasEveryCatalogTool() {
		McpResponse response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
		JsonArray tools = json(response).getAsJsonObject("result").getAsJsonArray("tools");
		assertEquals(23, tools.size());
		for (var tool : tools) {
			JsonObject t = tool.getAsJsonObject();
			assertFalse(t.get("name").getAsString().isEmpty());
			assertFalse(t.get("description").getAsString().isEmpty());
			assertEquals("object", t.getAsJsonObject("inputSchema").get("type").getAsString());
		}
		// Name first, then description: the order the fake Claude Code relies on to read the list.
		assertTrue(response.body().contains("{\"name\":\"get_context\",\"description\":"), response.body());
	}

	@Test
	void toolsCallReachesTheSession() {
		JsonObject result = json(post(call("fill", "{\"box\":[0,0,0,1,0,0],\"block\":\"stone\"}"))).getAsJsonObject("result");
		assertEquals("Queued 2 blocks.", result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
		assertEquals("text", result.getAsJsonArray("content").get(0).getAsJsonObject().get("type").getAsString());
		assertFalse(result.get("isError").getAsBoolean());
		assertEquals(List.of("fill {\"box\":[0,0,0,1,0,0],\"block\":\"stone\"}"), calls);
	}

	@Test
	void aToolErrorIsAnIsErrorResult() {
		JsonObject reply = json(post(call("nope", "{}")));
		assertTrue(reply.getAsJsonObject("result").get("isError").getAsBoolean());
		assertFalse(reply.has("error"), "a tool's failure is Claude's to act on, not a protocol error");
	}

	@Test
	void missingArgumentsAreAnEmptyObject() {
		post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"fill\"}}");
		assertEquals(List.of("fill {}"), calls);
		JsonObject reply = json(post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"fill\",\"arguments\":\"x\"}}"));
		assertEquals(-32602, reply.getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	void notReadyIsRefused() {
		ready = false;
		JsonObject result = json(post(call("fill", "{}"))).getAsJsonObject("result");
		assertTrue(result.get("isError").getAsBoolean());
		assertEquals("AI Builder hasn't cleared this Claude Code session to build.",
				result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
		assertTrue(calls.isEmpty());
	}

	@Test
	void aFailingToolIsAnIsErrorResult() {
		McpSessions own = new McpSessions();
		String t = own.open(new McpSession() {
			@Override
			public boolean awaitReady(long millis) {
				return true;
			}

			@Override
			public ToolResult call(String name, JsonObject arguments) {
				throw new IllegalStateException("boom");
			}
		});
		McpResponse response = new McpHandler(ToolCatalog.load().tools(), own).handle(new McpRequest("POST", Map.of("authorization", "Bearer " + t),
				call("fill", "{}")));
		assertEquals(200, response.status());
		assertTrue(json(response).getAsJsonObject("result").get("isError").getAsBoolean());
	}

	@Test
	void revokedTokenIsRefused() {
		sessions.revoke(token);
		McpResponse response = post(call("fill", "{}"));
		assertEquals(401, response.status());
		assertTrue(json(response).has("error"));
		assertTrue(calls.isEmpty());
	}

	@Test
	void missingTokenIsRefused() {
		assertEquals(401, post(call("fill", "{}"), Map.of()).status());
		assertEquals(401, post(call("fill", "{}"), Map.of("authorization", "Bearer " + "0".repeat(64))).status());
		assertEquals(401, post(call("fill", "{}"), Map.of("authorization", "Basic " + token)).status());
		assertTrue(calls.isEmpty());
	}

	@Test
	void foreignOriginIsRefused() {
		for (String foreign : List.of("https://evil.example", "http://127.0.0.1.evil.example", "null", "file://")) {
			Map<String, String> headers = new HashMap<>(Map.of("authorization", "Bearer " + token));
			headers.put("origin", foreign);
			assertEquals(403, post(call("fill", "{}"), headers).status(), foreign);
		}
		for (String local : List.of("http://localhost:5173", "http://127.0.0.1:1234", "https://localhost", "http://[::1]:80")) {
			Map<String, String> headers = new HashMap<>(Map.of("authorization", "Bearer " + token));
			headers.put("origin", local);
			assertEquals(200, post(call("fill", "{}"), headers).status(), local);
		}
		assertEquals(4, calls.size());
	}

	@Test
	void getIs405() {
		assertEquals(405, handler.handle(new McpRequest("GET", Map.of("authorization", "Bearer " + token), "")).status());
		assertEquals(405, handler.handle(new McpRequest("DELETE", Map.of("authorization", "Bearer " + token), "")).status());
	}

	@Test
	void badJsonIs400() {
		McpResponse response = post("{not json");
		assertEquals(400, response.status());
		assertEquals(-32700, json(response).getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	void batchesAreRefused() {
		McpResponse response = post("[" + call("fill", "{}") + "]");
		assertEquals(400, response.status());
		assertEquals(-32600, json(response).getAsJsonObject("error").get("code").getAsInt());
		assertTrue(calls.isEmpty());
	}

	@Test
	void pingAnswersEmpty() {
		assertEquals(new JsonObject(), json(post("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"ping\"}")).getAsJsonObject("result"));
	}
}
