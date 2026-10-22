package io.palmisano.aibuilder.core.request;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

/** The player's recent prompts, newest first, and the size they picked last. Immutable. */
public record PromptHistory(List<String> recent, SizeTier lastSize) {
	public static final int MAX = 10;

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	public PromptHistory {
		recent = List.copyOf(recent);
	}

	public static PromptHistory empty() {
		return new PromptHistory(List.of(), SizeTier.AUTO);
	}

	/** This history with {@code prompt} first (moved there if already listed), keeping the newest {@link #MAX}. A blank prompt changes nothing. */
	public PromptHistory withPrompt(String prompt) {
		String newest = BuildPrompt.normalize(prompt);
		if (newest.isEmpty()) {
			return this;
		}
		List<String> next = new ArrayList<>(MAX);
		next.add(newest);
		for (String older : recent) {
			if (next.size() == MAX) {
				break;
			}
			if (!older.equals(newest)) {
				next.add(older);
			}
		}
		return new PromptHistory(next, lastSize);
	}

	public PromptHistory withSize(SizeTier size) {
		return new PromptHistory(recent, size);
	}

	public String toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("lastSize", lastSize.name());
		JsonArray prompts = new JsonArray();
		recent.forEach(prompts::add);
		root.add("recent", prompts);
		return GSON.toJson(root);
	}

	/** Reads what {@link #toJson} wrote. Anything unreadable gives {@link #empty()}, never an exception. */
	public static PromptHistory fromJson(String json) {
		if (json == null) {
			return empty();
		}
		try {
			JsonElement root = JsonParser.parseString(json);
			if (!root.isJsonObject()) {
				return empty();
			}
			JsonObject object = root.getAsJsonObject();
			JsonElement size = object.get("lastSize");
			List<String> prompts = new ArrayList<>();
			if (object.get("recent") instanceof JsonArray array) {
				for (JsonElement entry : array) {
					if (prompts.size() == MAX) {
						break;
					}
					if (isString(entry) && !BuildPrompt.normalize(entry.getAsString()).isEmpty()) {
						prompts.add(BuildPrompt.normalize(entry.getAsString()));
					}
				}
			}
			return new PromptHistory(prompts, SizeTier.parse(isString(size) ? size.getAsString() : null));
		} catch (JsonParseException e) {
			return empty();
		}
	}

	private static boolean isString(JsonElement element) {
		return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
	}
}
