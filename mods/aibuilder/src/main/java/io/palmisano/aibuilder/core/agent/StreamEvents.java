package io.palmisano.aibuilder.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Reads Claude Code's stream one line at a time. Claude Code updates itself, so nothing here may throw: a missing
 * field takes a default, a field of the wrong type counts as missing, unknown fields and event types are ignored.
 */
public final class StreamEvents {
	private StreamEvents() {
	}

	/** The event on one line; empty for a blank line or anything that isn't a JSON object with a type. */
	public static Optional<StreamEvent> parse(String line) {
		if (line == null || line.isBlank()) {
			return Optional.empty();
		}
		JsonObject o;
		try {
			JsonElement element = JsonParser.parseString(line);
			if (!element.isJsonObject()) {
				return Optional.empty();
			}
			o = element.getAsJsonObject();
		} catch (RuntimeException e) {
			return Optional.empty();
		}
		String type = string(o, "type");
		if (type.isEmpty()) {
			return Optional.empty();
		}
		String subtype = string(o, "subtype");
		return Optional.of(switch (type) {
			case "system" -> switch (subtype) {
				case "init" -> init(o);
				case "api_retry" -> new StreamEvent.Retry(integer(o, "attempt"), string(o, "error"));
				default -> new StreamEvent.Other(subtype.isEmpty() ? "system" : "system/" + subtype);
			};
			case "assistant" -> assistant(o);
			case "stream_event" -> {
				JsonObject event = object(o, "event");
				JsonObject usage = object(event, "usage");
				yield "message_delta".equals(string(event, "type"))
						? new StreamEvent.Usage(number(usage, "input_tokens"), number(usage, "output_tokens"), number(usage, "cache_read_input_tokens"),
								number(usage, "cache_creation_input_tokens"))
						: new StreamEvent.Other("stream_event");
			}
			case "rate_limit_event" -> {
				JsonObject info = object(o, "rate_limit_info");
				double fullest = 0;
				JsonObject windows = object(info, "unifiedWindows");
				if (windows != null) {
					for (String window : windows.keySet()) {
						fullest = Math.max(fullest, decimal(object(windows, window), "utilization"));
					}
				}
				yield new StreamEvent.RateLimit(string(info, "status"), number(info, "resetsAt"), string(info, "rateLimitType"),
						bool(info, "isUsingOverage"), string(info, "overageStatus"), fullest);
			}
			case "result" -> new StreamEvent.Result(subtype, bool(o, "is_error"), integer(o, "num_turns"), string(o, "result"),
					string(o, "terminal_reason"));
			default -> new StreamEvent.Other(type);
		});
	}

	private static StreamEvent.Init init(JsonObject o) {
		List<String> tools = new ArrayList<>();
		for (JsonElement tool : array(o, "tools")) {
			if (tool.isJsonPrimitive()) {
				tools.add(tool.getAsString());
			}
		}
		List<StreamEvent.Server> servers = new ArrayList<>();
		for (JsonElement server : array(o, "mcp_servers")) {
			if (server.isJsonObject()) {
				servers.add(new StreamEvent.Server(string(server.getAsJsonObject(), "name"), string(server.getAsJsonObject(), "status")));
			}
		}
		List<StreamEvent.Plugin> plugins = new ArrayList<>();
		for (JsonElement plugin : array(o, "plugins")) {
			if (plugin.isJsonObject()) {
				plugins.add(new StreamEvent.Plugin(string(plugin.getAsJsonObject(), "name"), string(plugin.getAsJsonObject(), "path")));
			}
		}
		return new StreamEvent.Init(string(o, "session_id"), string(o, "model"), string(o, "claude_code_version"), List.copyOf(tools),
				List.copyOf(servers), string(o, "apiKeySource"), List.copyOf(plugins));
	}

	private static StreamEvent.Assistant assistant(JsonObject o) {
		JsonObject message = object(o, "message");
		List<String> tools = new ArrayList<>();
		List<String> texts = new ArrayList<>();
		for (JsonElement block : array(message, "content")) {
			if (!block.isJsonObject()) {
				continue;
			}
			JsonObject b = block.getAsJsonObject();
			switch (string(b, "type")) {
				case "tool_use" -> tools.add(string(b, "name"));
				case "text" -> texts.add(string(b, "text"));
				default -> {
				}
			}
		}
		return new StreamEvent.Assistant(string(message, "id"), List.copyOf(tools), String.join("\n", texts), string(o, "error"));
	}

	private static JsonObject object(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
	}

	private static JsonArray array(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
	}

	private static long number(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		if (e instanceof JsonPrimitive p && p.isNumber()) {
			return p.getAsLong();
		}
		return 0;
	}

	private static double decimal(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		if (e instanceof JsonPrimitive p && p.isNumber()) {
			double value = p.getAsDouble();
			return Double.isFinite(value) ? value : 0;
		}
		return 0;
	}

	private static int integer(JsonObject o, String key) {
		return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, number(o, key)));
	}

	private static boolean bool(JsonObject o, String key) {
		JsonElement e = o == null ? null : o.get(key);
		return e instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
	}
}
