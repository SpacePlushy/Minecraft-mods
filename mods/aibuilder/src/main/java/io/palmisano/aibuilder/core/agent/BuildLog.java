package io.palmisano.aibuilder.core.agent;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.palmisano.aibuilder.core.tools.ToolResult;

/**
 * A build's log (spec §16), one JSON object a line: the request, Claude Code's start, every tool call (its arguments
 * with arrays over {@link #ARRAY_LIMIT} shortened, the first line of its answer, its rejections, how long it took),
 * each model turn's tokens, the outcome and the totals. Every line is scrubbed before it goes to {@code out} (the file
 * writer, which writes off the server thread). Thread-safe: Claude Code may send a turn's calls at once.
 */
public final class BuildLog {
	public static final int ARRAY_LIMIT = 20;
	public static final int RESULT_LIMIT = 300;
	private static final int SLUG_LIMIT = 40;
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT);
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final Consumer<String> out;
	private final Scrub scrub;
	private final LongSupplier clock;
	private final long started;
	private int calls;
	private int refused;
	private int turns;
	private long input;
	private long output;
	private long cacheRead;
	private long cacheWrite;

	/** {@code clock}: milliseconds since the epoch. */
	public BuildLog(Consumer<String> out, Scrub scrub, LongSupplier clock) {
		this.out = out;
		this.scrub = scrub;
		this.clock = clock;
		this.started = clock.getAsLong();
	}

	/** {@code refines}: the title of the build a refinement changes ("" for a new build). */
	public synchronized void request(String prompt, String size, boolean refine, String refines, String model, String effort, String session) {
		JsonObject line = line("request");
		line.addProperty("prompt", prompt);
		line.addProperty("size", size);
		line.addProperty("mode", refine ? "refine" : "new");
		if (refine) {
			line.addProperty("refines", refines);
		}
		line.addProperty("model", model);
		line.addProperty("effort", effort);
		line.addProperty("session", session);
		write(line);
	}

	/** Claude Code's start line: its version and the model it reported. */
	public synchronized void started(String version, String model) {
		JsonObject line = line("claude");
		line.addProperty("claude_code_version", version);
		line.addProperty("model", model);
		write(line);
	}

	public synchronized void call(String name, JsonObject args, ToolResult result, long millis) {
		calls++;
		if (result.isError()) {
			refused++;
		}
		JsonObject line = line("tool");
		line.addProperty("name", name);
		line.add("args", args == null ? new JsonObject() : shortened(args));
		line.addProperty("error", result.isError());
		line.addProperty("result", firstLine(result.text()));
		if (!result.rejections().isEmpty()) {
			JsonArray rejections = new JsonArray();
			result.rejections().forEach(rejections::add);
			line.add("rejections", shortened(rejections));
		}
		line.addProperty("ms", millis);
		write(line);
	}

	/** One model turn's tokens. */
	public synchronized void usage(long input, long output, long cacheRead, long cacheWrite) {
		turns++;
		this.input += input;
		this.output += output;
		this.cacheRead += cacheRead;
		this.cacheWrite += cacheWrite;
		JsonObject line = line("usage");
		line.addProperty("input_tokens", input);
		line.addProperty("output_tokens", output);
		line.addProperty("cache_read_tokens", cacheRead);
		line.addProperty("cache_write_tokens", cacheWrite);
		write(line);
	}

	/** How the build ended ("done", "stopped", "capped", "limited", "failed") and the player's line, then the totals. */
	public synchronized void outcome(String outcome, String chatLine, int placed, int skipped) {
		JsonObject line = line("outcome");
		line.addProperty("outcome", outcome);
		line.addProperty("line", chatLine);
		line.addProperty("placed", placed);
		line.addProperty("skipped", skipped);
		write(line);
		JsonObject totals = line("totals");
		totals.addProperty("tool_calls", calls);
		totals.addProperty("refused_calls", refused);
		totals.addProperty("turns", turns);
		totals.addProperty("input_tokens", input);
		totals.addProperty("output_tokens", output);
		totals.addProperty("cache_read_tokens", cacheRead);
		totals.addProperty("cache_write_tokens", cacheWrite);
		totals.addProperty("seconds", (clock.getAsLong() - started) / 1000.0);
		write(totals);
	}

	/** A copy with every array over {@link #ARRAY_LIMIT} cut to its first ones and a "… N more" at the end. */
	public static JsonElement shortened(JsonElement json) {
		if (json.isJsonArray()) {
			JsonArray array = json.getAsJsonArray();
			JsonArray copy = new JsonArray();
			for (int i = 0; i < Math.min(array.size(), ARRAY_LIMIT); i++) {
				copy.add(shortened(array.get(i)));
			}
			if (array.size() > ARRAY_LIMIT) {
				copy.add("… " + (array.size() - ARRAY_LIMIT) + " more");
			}
			return copy;
		}
		if (json.isJsonObject()) {
			JsonObject copy = new JsonObject();
			for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
				copy.add(entry.getKey(), shortened(entry.getValue()));
			}
			return copy;
		}
		return json;
	}

	/** The log's name without its extension: when, who, and the prompt's first words. */
	public static String baseName(LocalDateTime time, String player, String prompt) {
		return time.format(FILE_TIME) + "_" + player.replaceAll("[^A-Za-z0-9_]", "_") + "_" + slug(prompt);
	}

	/** The prompt's words in lowercase ASCII joined by dashes, at most 40 characters; "build" when nothing is left. */
	public static String slug(String prompt) {
		String plain = Normalizer.normalize(prompt, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
		String slug = plain.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		if (slug.length() > SLUG_LIMIT) {
			slug = slug.substring(0, SLUG_LIMIT).replaceAll("-+$", "");
		}
		return slug.isEmpty() ? "build" : slug;
	}

	private JsonObject line(String type) {
		JsonObject line = new JsonObject();
		line.addProperty("time", Instant.ofEpochMilli(clock.getAsLong()).toString());
		line.addProperty("type", type);
		return line;
	}

	/** Every text in it scrubbed as it is, before JSON escapes it, then the line once more. */
	private void write(JsonObject line) {
		out.accept(scrub.apply(GSON.toJson(scrubbed(line))));
	}

	private JsonElement scrubbed(JsonElement json) {
		if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
			return new JsonPrimitive(scrub.apply(json.getAsString()));
		}
		if (json.isJsonArray()) {
			JsonArray copy = new JsonArray();
			json.getAsJsonArray().forEach(item -> copy.add(scrubbed(item)));
			return copy;
		}
		if (json.isJsonObject()) {
			JsonObject copy = new JsonObject();
			json.getAsJsonObject().entrySet().forEach(entry -> copy.add(entry.getKey(), scrubbed(entry.getValue())));
			return copy;
		}
		return json;
	}

	/** A result's first line, cut to {@link #RESULT_LIMIT} code points with "…". */
	private static String firstLine(String text) {
		int end = text.indexOf('\n');
		String first = (end < 0 ? text : text.substring(0, end)).strip();
		if (first.codePointCount(0, first.length()) <= RESULT_LIMIT) {
			return first;
		}
		return first.substring(0, first.offsetByCodePoints(0, RESULT_LIMIT - 1)) + "…";
	}
}
