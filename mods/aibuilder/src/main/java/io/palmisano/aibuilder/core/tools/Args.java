package io.palmisano.aibuilder.core.tools;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.Box;

/** Reads a tool call's arguments; anything missing or malformed becomes a {@link BadArgument} naming it. */
final class Args {
	static final class BadArgument extends IllegalArgumentException {
		BadArgument(String message) {
			super(message);
		}
	}

	private final JsonObject json;

	Args(JsonObject json) {
		this.json = json == null ? new JsonObject() : json;
	}

	/** The arguments as they came, for a parser of its own (place_block_entity's data). */
	JsonObject json() {
		return json;
	}

	boolean has(String name) {
		JsonElement e = json.get(name);
		return e != null && !e.isJsonNull();
	}

	private JsonElement required(String name) {
		if (!has(name)) {
			throw new BadArgument(name + " is required");
		}
		return json.get(name);
	}

	String string(String name) {
		JsonElement e = required(name);
		if (!e.isJsonPrimitive()) {
			throw new BadArgument(name + " must be a string");
		}
		return e.getAsString();
	}

	String optString(String name, String fallback) {
		return has(name) ? string(name) : fallback;
	}

	int integer(String name) {
		return asInt(required(name), name);
	}

	int optInt(String name, int fallback) {
		return has(name) ? integer(name) : fallback;
	}

	boolean optBool(String name, boolean fallback) {
		if (!has(name)) {
			return fallback;
		}
		JsonElement e = json.get(name);
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
			throw new BadArgument(name + " must be true or false");
		}
		return e.getAsBoolean();
	}

	JsonObject object(String name) {
		JsonElement e = required(name);
		if (!e.isJsonObject()) {
			throw new BadArgument(name + " must be an object");
		}
		return e.getAsJsonObject();
	}

	JsonArray array(String name) {
		JsonElement e = required(name);
		if (!e.isJsonArray()) {
			throw new BadArgument(name + " must be a list");
		}
		return e.getAsJsonArray();
	}

	List<String> strings(String name) {
		List<String> out = new ArrayList<>();
		for (JsonElement e : array(name)) {
			if (!e.isJsonPrimitive()) {
				throw new BadArgument(name + " must be a list of strings");
			}
			out.add(e.getAsString());
		}
		return out;
	}

	int[] ints(String name, int length) {
		JsonArray a = array(name);
		if (a.size() != length) {
			throw new BadArgument(name + " must be a list of " + length + " whole numbers");
		}
		int[] out = new int[length];
		for (int i = 0; i < length; i++) {
			out[i] = asInt(a.get(i), name);
		}
		return out;
	}

	/** A list of {@code length} numbers, fractions allowed, each within ±{@link Tunables#ARGUMENT_LIMIT}. */
	double[] numbers(String name, int length) {
		JsonArray a = array(name);
		if (a.size() != length) {
			throw new BadArgument(name + " must be a list of " + length + " numbers");
		}
		double[] out = new double[length];
		for (int i = 0; i < length; i++) {
			JsonElement e = a.get(i);
			if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !Double.isFinite(e.getAsDouble())
					|| Math.abs(e.getAsDouble()) > Tunables.ARGUMENT_LIMIT) {
				throw new BadArgument(name + " must be a list of " + length + " numbers");
			}
			out[i] = e.getAsDouble();
		}
		return out;
	}

	Pos pos(String name) {
		int[] p = ints(name, 3);
		return new Pos(p[0], p[1], p[2]);
	}

	Box box(String name) {
		int[] b = ints(name, 6);
		return Box.of(b[0], b[1], b[2], b[3], b[4], b[5]);
	}

	/** A list of {@code [x, y, z]} positions, or one alone. */
	List<Pos> positions(String name) {
		JsonArray list = array(name);
		if (!list.isEmpty() && list.get(0).isJsonPrimitive()) {
			return List.of(pos(name));
		}
		List<Pos> out = new ArrayList<>();
		for (JsonElement e : list) {
			if (!e.isJsonArray() || e.getAsJsonArray().size() != 3) {
				throw new BadArgument(name + " must be a list of [x, y, z] positions");
			}
			JsonArray p = e.getAsJsonArray();
			out.add(new Pos(asInt(p.get(0), name), asInt(p.get(1), name), asInt(p.get(2), name)));
		}
		return out;
	}

	/** A list of {@code [x, z]} points. */
	List<int[]> points(String name) {
		List<int[]> out = new ArrayList<>();
		for (JsonElement e : array(name)) {
			if (!e.isJsonArray() || e.getAsJsonArray().size() != 2) {
				throw new BadArgument(name + " must be a list of [x, z] points");
			}
			out.add(new int[] {asInt(e.getAsJsonArray().get(0), name), asInt(e.getAsJsonArray().get(1), name)});
		}
		return out;
	}

	/** The block id at {@code name}, with the state object from {@code "state"} when there is one. */
	BlockSpec block(String name) {
		return new BlockSpec(string(name), state("state"));
	}

	Map<String, String> state(String name) {
		if (!has(name)) {
			return Map.of();
		}
		JsonElement e = json.get(name);
		if (!e.isJsonObject()) {
			throw new BadArgument(name + " must be an object of property names to values");
		}
		Map<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
			if (!entry.getValue().isJsonPrimitive()) {
				throw new BadArgument(name + "." + entry.getKey() + " must be a plain value");
			}
			out.put(entry.getKey(), entry.getValue().getAsString());
		}
		return out;
	}

	/** A whole number within ±{@link Tunables#ARGUMENT_LIMIT}; a fraction or a huge number is refused, never rounded. */
	private static int asInt(JsonElement e, String name) {
		BigDecimal value;
		try {
			if (!e.isJsonPrimitive()) {
				throw new BadArgument(name + " must hold whole numbers");
			}
			value = e.getAsBigDecimal();
		} catch (NumberFormatException | UnsupportedOperationException | IllegalStateException ex) {
			throw new BadArgument(name + " must hold whole numbers");
		}
		if (value.signum() != 0 && value.stripTrailingZeros().scale() > 0) {
			throw new BadArgument(name + " must hold whole numbers, not " + value.toPlainString());
		}
		if (value.abs().compareTo(BigDecimal.valueOf(Tunables.ARGUMENT_LIMIT)) > 0) {
			throw new BadArgument(name + " must be between -" + Tunables.ARGUMENT_LIMIT + " and " + Tunables.ARGUMENT_LIMIT);
		}
		return value.intValueExact();
	}
}
