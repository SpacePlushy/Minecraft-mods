package io.palmisano.aibuilder.core.config;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.blocks.Validator;
import io.palmisano.aibuilder.core.request.SizeTier;

/**
 * {@code config/aibuilder.json} (spec §14): what a player may change, each key with its default. A value that's the
 * wrong type or out of range falls back to its default with a warning, so a hand-edited file never stops the mod.
 * No API key anywhere: Claude Code does its own login.
 */
public record AiBuilderConfig(String backend, String model, String effort, String claudePath, Map<SizeTier, Long> sizeLimits,
		int blocksPerTick, boolean animateBuild, int maxToolIterations, long maxTokensPerBuild, int maxBuildMinutes, boolean requireOp,
		int opLevel, List<String> allowedPlayers, int rateLimitPerHour, int undoHistorySize, boolean protectPlayerBlocks, int siteGap,
		List<String> bannedBlocks) {
	public static final List<String> BACKENDS = List.of("claude_code", "scripted");
	public static final List<String> EFFORTS = List.of("low", "medium", "high", "xhigh", "max");
	/** With animate_build off, how many times more blocks a tick (the per-tick time cap still holds). */
	public static final int UNANIMATED_FACTOR = 8;

	public static final AiBuilderConfig DEFAULT = new AiBuilderConfig("claude_code", "claude-opus-5-5", "high", "", defaultLimits(),
			Tunables.BLOCKS_PER_TICK, true, Tunables.MAX_TOOL_CALLS, Tunables.MAX_OUTPUT_TOKENS, Tunables.MAX_BUILD_MINUTES, true, 2, List.of(),
			20, Tunables.UNDO_HISTORY, true, Tunables.SITE_GAP, Validator.DEFAULT_BANNED.stream().sorted().toList());

	public record Loaded(AiBuilderConfig config, List<String> warnings) {
	}

	public AiBuilderConfig {
		sizeLimits = Map.copyOf(sizeLimits);
		allowedPlayers = List.copyOf(allowedPlayers);
		bannedBlocks = List.copyOf(bannedBlocks);
	}

	/** Blocks changed per build at most, for the tier (Auto builds at Large's). */
	public long sizeLimit(SizeTier tier) {
		return sizeLimits.get(tier == SizeTier.AUTO ? SizeTier.LARGE : tier);
	}

	/** Blocks placed a tick at most. */
	public int placementRate() {
		return animateBuild ? blocksPerTick : blocksPerTick * UNANIMATED_FACTOR;
	}

	/** Reads the file's text; never throws. Keys starting with {@code _} are comments. */
	public static Loaded load(String json) {
		List<String> warnings = new ArrayList<>();
		JsonObject o;
		try {
			JsonElement parsed = JsonParser.parseString(json);
			if (!parsed.isJsonObject()) {
				return new Loaded(DEFAULT, List.of("config/aibuilder.json isn't a JSON object; using the defaults"));
			}
			o = parsed.getAsJsonObject();
		} catch (JsonParseException e) {
			return new Loaded(DEFAULT, List.of("config/aibuilder.json isn't valid JSON (" + e.getMessage() + "); using the defaults"));
		}
		Reader r = new Reader(o, warnings);
		AiBuilderConfig d = DEFAULT;
		AiBuilderConfig config = new AiBuilderConfig(
				r.oneOf("backend", d.backend, BACKENDS),
				r.string("model", d.model, v -> v.matches("[a-z0-9][a-z0-9.\\-\\[\\]]*") ? null : "isn't a model name"),
				r.oneOf("effort", d.effort, EFFORTS),
				r.string("claude_path", d.claudePath, v -> null),
				r.sizeLimits(),
				(int) r.number("blocks_per_tick", d.blocksPerTick, 1, 10_000),
				r.bool("animate_build", d.animateBuild),
				(int) r.number("max_tool_iterations", d.maxToolIterations, 1, 1_000),
				r.number("max_tokens_per_build", d.maxTokensPerBuild, 1_000, 5_000_000),
				(int) r.number("max_build_minutes", d.maxBuildMinutes, 1, 240),
				r.bool("require_op", d.requireOp),
				(int) r.number("op_level", d.opLevel, 0, 4),
				r.list("allowed_players", d.allowedPlayers, v -> v.isBlank() ? "" : v.strip(), false),
				(int) r.number("rate_limit_per_hour", d.rateLimitPerHour, 0, 1_000),
				(int) r.number("undo_history_size", d.undoHistorySize, 1, 50),
				r.bool("protect_player_blocks", d.protectPlayerBlocks),
				(int) r.number("site_gap", d.siteGap, 1, 16),
				// A list of nothing but typos must not unban TNT and fire; an empty list is the owner's choice (M6 review).
				r.list("banned_blocks", d.bannedBlocks, AiBuilderConfig::blockId, true));
		for (String key : o.keySet()) {
			if (!key.startsWith("_") && !Reader.KEYS.contains(key)) {
				warnings.add("config/aibuilder.json: unknown key \"" + key + "\" ignored");
			}
		}
		return new Loaded(config, List.copyOf(warnings));
	}

	/** The file as the mod writes it on the first run: every key with its value, and what to know about them. */
	public String toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("_comment", "AI Builder's settings. A value that's wrong falls back to its default, with a warning in the log.");
		o.addProperty("backend", backend);
		o.addProperty("model", model);
		o.addProperty("effort", effort);
		o.addProperty("_comment_claude_path", "Where Claude Code is, if AI Builder doesn't find it by itself; empty to search.");
		o.addProperty("claude_path", claudePath);
		JsonObject limits = new JsonObject();
		for (SizeTier tier : List.of(SizeTier.SMALL, SizeTier.MEDIUM, SizeTier.LARGE, SizeTier.EPIC)) {
			limits.addProperty(tier.name().toLowerCase(Locale.ROOT), sizeLimits.get(tier));
		}
		o.addProperty("_comment_size_limits", "Blocks one build may change, per size.");
		o.add("size_limits", limits);
		o.addProperty("blocks_per_tick", blocksPerTick);
		o.addProperty("animate_build", animateBuild);
		o.addProperty("max_tool_iterations", maxToolIterations);
		o.addProperty("max_tokens_per_build", maxTokensPerBuild);
		o.addProperty("max_build_minutes", maxBuildMinutes);
		o.addProperty("_comment_allowed_players", "Dedicated servers only: who may build. Builds run on the Claude Code login of whoever runs "
				+ "the server, and Anthropic's terms describe a Pro or Max plan as one person's own use, so list only yourself.");
		o.add("allowed_players", strings(allowedPlayers));
		o.addProperty("require_op", requireOp);
		o.addProperty("op_level", opLevel);
		o.addProperty("rate_limit_per_hour", rateLimitPerHour);
		o.addProperty("undo_history_size", undoHistorySize);
		o.addProperty("protect_player_blocks", protectPlayerBlocks);
		o.addProperty("site_gap", siteGap);
		o.addProperty("_comment_banned_blocks", "Never placed. Unbreakable blocks (bedrock, barriers, command blocks…) are never placed anyway.");
		o.add("banned_blocks", strings(bannedBlocks));
		return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o) + "\n";
	}

	private static JsonArray strings(List<String> values) {
		JsonArray a = new JsonArray();
		values.forEach(a::add);
		return a;
	}

	private static Map<SizeTier, Long> defaultLimits() {
		Map<SizeTier, Long> limits = new EnumMap<>(SizeTier.class);
		for (SizeTier tier : List.of(SizeTier.SMALL, SizeTier.MEDIUM, SizeTier.LARGE, SizeTier.EPIC)) {
			limits.put(tier, tier.maxChangedBlocks());
		}
		return limits;
	}

	/** A block id, "minecraft:" added when it has no namespace; "" when it isn't one. */
	private static String blockId(String raw) {
		String id = raw.strip().toLowerCase(Locale.ROOT);
		if (!id.contains(":")) {
			id = "minecraft:" + id;
		}
		return id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? id : "";
	}

	/** Reads one key at a time, warning and falling back on anything wrong. */
	private static final class Reader {
		static final Set<String> KEYS = Set.of("backend", "model", "effort", "claude_path", "size_limits", "blocks_per_tick", "animate_build",
				"max_tool_iterations", "max_tokens_per_build", "max_build_minutes", "require_op", "op_level", "allowed_players",
				"rate_limit_per_hour", "undo_history_size", "protect_player_blocks", "site_gap", "banned_blocks");

		private final JsonObject o;
		private final List<String> warnings;

		Reader(JsonObject o, List<String> warnings) {
			this.o = o;
			this.warnings = warnings;
		}

		private JsonElement get(String key) {
			JsonElement e = o.get(key);
			return e == null || e.isJsonNull() ? null : e;
		}

		private <T> T fallBack(String key, T fallback, String why) {
			warnings.add("config/aibuilder.json: " + key + " " + why + "; using " + fallback);
			return fallback;
		}

		String string(String key, String fallback, Function<String, String> problem) {
			JsonElement e = get(key);
			if (e == null) {
				return fallback;
			}
			if (!(e instanceof JsonPrimitive p) || !p.isString()) {
				return fallBack(key, fallback, "must be text");
			}
			String why = problem.apply(p.getAsString().strip());
			return why == null ? p.getAsString().strip() : fallBack(key, fallback, why);
		}

		String oneOf(String key, String fallback, List<String> allowed) {
			return string(key, fallback, v -> allowed.contains(v) ? null : "must be one of " + String.join(", ", allowed));
		}

		boolean bool(String key, boolean fallback) {
			JsonElement e = get(key);
			if (e == null) {
				return fallback;
			}
			return e instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : fallBack(key, fallback, "must be true or false");
		}

		long number(String key, long fallback, long min, long max) {
			return number(key, get(key), fallback, min, max);
		}

		private long number(String key, JsonElement e, long fallback, long min, long max) {
			if (e == null) {
				return fallback;
			}
			if (!(e instanceof JsonPrimitive p) || !p.isNumber() || p.getAsDouble() != Math.rint(p.getAsDouble())) {
				return fallBack(key, fallback, "must be a whole number");
			}
			double value = p.getAsDouble();
			return value >= min && value <= max ? (long) value : fallBack(key, fallback, "must be from " + min + " to " + max);
		}

		Map<SizeTier, Long> sizeLimits() {
			Map<SizeTier, Long> limits = new EnumMap<>(DEFAULT == null ? defaultLimits() : DEFAULT.sizeLimits);
			JsonElement e = get("size_limits");
			if (e == null) {
				return limits;
			}
			if (!e.isJsonObject()) {
				return fallBack("size_limits", limits, "must be an object of small, medium, large and epic");
			}
			for (SizeTier tier : List.of(SizeTier.SMALL, SizeTier.MEDIUM, SizeTier.LARGE, SizeTier.EPIC)) {
				String name = tier.name().toLowerCase(Locale.ROOT);
				limits.put(tier, number("size_limits." + name, e.getAsJsonObject().get(name), limits.get(tier), 1, Tunables.PENDING_MAX));
			}
			return limits;
		}

		/**
		 * Each entry through {@code clean}; "" drops an entry (with a warning unless it was empty to begin with).
		 * {@code fallBackWhenNoneValid}: a list that had entries and kept none is the default instead.
		 */
		List<String> list(String key, List<String> fallback, Function<String, String> clean, boolean fallBackWhenNoneValid) {
			JsonElement e = get(key);
			if (e == null) {
				return fallback;
			}
			if (!e.isJsonArray()) {
				return fallBack(key, fallback, "must be a list");
			}
			Map<String, Boolean> out = new LinkedHashMap<>();
			for (JsonElement item : e.getAsJsonArray()) {
				if (!(item instanceof JsonPrimitive p) || !p.isString()) {
					warnings.add("config/aibuilder.json: " + key + " holds " + item + ", which isn't text; left out");
					continue;
				}
				String value = clean.apply(p.getAsString());
				if (value.isEmpty()) {
					if (!p.getAsString().isBlank()) {
						warnings.add("config/aibuilder.json: " + key + " holds \"" + p.getAsString() + "\", which isn't valid; left out");
					}
					continue;
				}
				out.put(value, true);
			}
			if (out.isEmpty() && !e.getAsJsonArray().isEmpty() && fallBackWhenNoneValid) {
				return fallBack(key, fallback, "holds no valid block id, so the default list stands");
			}
			return List.copyOf(out.keySet());
		}
	}
}
