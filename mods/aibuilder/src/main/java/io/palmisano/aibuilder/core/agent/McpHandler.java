package io.palmisano.aibuilder.core.agent;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.tools.ToolDef;
import io.palmisano.aibuilder.core.tools.ToolResult;

/**
 * The MCP server's protocol, without the transport (spec §12.2): JSON-RPC 2.0 over Streamable HTTP, answered with
 * plain {@code application/json} (the M4 spike). Every request needs a live build's bearer token; a non-local
 * {@code Origin} is refused (DNS rebinding); a refusal never hints at OAuth. Tool failures come back as
 * {@code isError} results Claude can act on, never as protocol errors.
 */
public final class McpHandler {
	public static final String LATEST_VERSION = "2025-11-25";
	private static final Set<String> VERSIONS = Set.of(LATEST_VERSION, "2025-06-18", "2025-03-26");
	private static final Set<String> LOCAL_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]", "::1");

	private final List<ToolDef> tools;
	private final McpSessions sessions;

	public McpHandler(List<ToolDef> tools, McpSessions sessions) {
		this.tools = List.copyOf(tools);
		this.sessions = sessions;
	}

	public McpResponse handle(McpRequest request) {
		String origin = request.headers().get("origin");
		if (origin != null && !local(origin)) {
			return new McpResponse(403, error(null, -32000, "Requests from web pages aren't accepted."));
		}
		Optional<McpSession> session = sessions.find(bearer(request.headers().get("authorization")));
		if (session.isEmpty()) {
			return new McpResponse(401, error(null, -32001, "This build's session has ended."));
		}
		if (!request.method().equals("POST")) {
			return new McpResponse(405, null);
		}
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(request.body() == null ? "" : request.body());
		} catch (RuntimeException e) {
			return new McpResponse(400, error(null, -32700, "Not JSON."));
		}
		if (parsed.isJsonArray()) {
			return new McpResponse(400, error(null, -32600, "Batches aren't supported."));
		}
		if (!parsed.isJsonObject()) {
			return new McpResponse(400, error(null, -32600, "Not a JSON-RPC message."));
		}
		JsonObject message = parsed.getAsJsonObject();
		JsonElement id = message.get("id");
		if (id == null || id.isJsonNull() || !message.has("method")) {
			// A notification (or a reply to a request this server never makes): nothing to answer.
			return new McpResponse(202, null);
		}
		JsonObject params = message.get("params") instanceof JsonObject p ? p : new JsonObject();
		return switch (string(message, "method")) {
			case "initialize" -> new McpResponse(200, result(id, initialize(string(params, "protocolVersion"))));
			case "tools/list" -> new McpResponse(200, result(id, list()));
			case "tools/call" -> call(id, params, session.get());
			case "ping" -> new McpResponse(200, result(id, new JsonObject()));
			default -> new McpResponse(200, error(id, -32601, "No such method."));
		};
	}

	private static JsonObject initialize(String asked) {
		JsonObject tools = new JsonObject();
		tools.addProperty("listChanged", false);
		JsonObject capabilities = new JsonObject();
		capabilities.add("tools", tools);
		JsonObject info = new JsonObject();
		info.addProperty("name", InitCheck.SERVER);
		info.addProperty("version", "1");
		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", VERSIONS.contains(asked) ? asked : LATEST_VERSION);
		result.add("capabilities", capabilities);
		result.add("serverInfo", info);
		return result;
	}

	private JsonObject list() {
		JsonArray list = new JsonArray();
		for (ToolDef tool : tools) {
			JsonObject t = new JsonObject();
			t.addProperty("name", tool.name());
			t.addProperty("description", tool.description());
			t.add("inputSchema", tool.inputSchema());
			list.add(t);
		}
		JsonObject result = new JsonObject();
		result.add("tools", list);
		return result;
	}

	private static McpResponse call(JsonElement id, JsonObject params, McpSession session) {
		JsonElement given = params.get("arguments");
		if (given != null && !given.isJsonNull() && !given.isJsonObject()) {
			return new McpResponse(200, error(id, -32602, "arguments must be an object."));
		}
		JsonObject arguments = given instanceof JsonObject o ? o : new JsonObject();
		ToolResult result;
		if (!session.awaitReady(Tunables.READY_WAIT_SECONDS * 1000L)) {
			result = new ToolResult("AI Builder hasn't cleared this Claude Code session to build.", true);
		} else {
			try {
				result = session.call(string(params, "name"), arguments);
			} catch (RuntimeException e) {
				result = new ToolResult("AI Builder failed on that call: " + e.getMessage(), true);
			}
		}
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", result.text());
		JsonArray content = new JsonArray();
		content.add(text);
		JsonObject body = new JsonObject();
		body.add("content", content);
		body.addProperty("isError", result.isError());
		return new McpResponse(200, result(id, body));
	}

	/** http(s) to 127.0.0.1, localhost or [::1], any port. */
	private static boolean local(String origin) {
		try {
			URI uri = URI.create(origin);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
			return (scheme.equals("http") || scheme.equals("https")) && LOCAL_HOSTS.contains(host);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static String bearer(String authorization) {
		if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
			return null;
		}
		return authorization.substring(7).strip();
	}

	private static String result(JsonElement id, JsonObject result) {
		JsonObject message = new JsonObject();
		message.addProperty("jsonrpc", "2.0");
		message.add("id", id);
		message.add("result", result);
		return message.toString();
	}

	private static String error(JsonElement id, int code, String text) {
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", text);
		JsonObject message = new JsonObject();
		message.addProperty("jsonrpc", "2.0");
		message.add("id", id);
		message.add("error", error);
		return message.toString();
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}
}
