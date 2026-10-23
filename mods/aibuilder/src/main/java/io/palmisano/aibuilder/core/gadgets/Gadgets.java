package io.palmisano.aibuilder.core.gadgets;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** The library place_gadget makes from (spec v2 §5.3): each kind and the options it takes. Pure. */
public final class Gadgets {
	public static final List<String> KINDS = List.of("lever_row", "lamp_row", "adder", "digit");
	/** The options each kind takes. */
	private static final Map<String, Set<String>> OPTIONS = Map.of("lever_row", Set.of("bits", "label"), "lamp_row", Set.of("bits"), "adder",
			Set.of("bits"), "digit", Set.of());

	private Gadgets() {
	}

	/** The gadget of {@code kind} with {@code options}; refused with what to change. */
	public static Gadget make(String kind, JsonObject options) {
		check(kind, options);
		return switch (kind) {
			case "lever_row" -> LeverRow.make(bits(options, 4), label(options));
			case "lamp_row" -> LampRow.make(bits(options, 4));
			case "adder" -> Adder.make(bits(options, 4));
			case "digit" -> Digit.make();
			default -> throw new IllegalStateException(kind);
		};
	}

	/** "an adder (4 bits)": the gadget in words, for place_gadget's answer. */
	public static String describe(String kind, JsonObject options) {
		check(kind, options);
		int bits = bits(options, 4);
		String count = bits + (bits == 1 ? " bit" : " bits");
		return switch (kind) {
			case "lever_row" -> "a row of " + bits + (bits == 1 ? " lever" : " levers") + " (" + label(options) + ")";
			case "lamp_row" -> "a row of " + bits + (bits == 1 ? " lamp" : " lamps");
			case "adder" -> "an adder (" + count + ")";
			case "digit" -> "a digit";
			default -> kind;
		};
	}

	private static void check(String kind, JsonObject options) {
		Set<String> takes = OPTIONS.get(kind);
		if (takes == null) {
			throw new IllegalArgumentException("there's no gadget called \"" + kind + "\": the kinds are " + String.join(", ", KINDS));
		}
		for (String name : options.keySet()) {
			if (!takes.contains(name)) {
				throw new IllegalArgumentException(kind + (takes.isEmpty() ? " takes no options" : " takes " + String.join(" and ", takes.stream().sorted().toList()))
						+ ", not " + name);
			}
		}
	}

	/** {@code bits}, 1 to 8, or {@code otherwise}. */
	static int bits(JsonObject options, int otherwise) {
		JsonElement given = options.get("bits");
		if (given == null) {
			return otherwise;
		}
		String text = given.isJsonPrimitive() ? given.getAsJsonPrimitive().toString() : given.toString();
		if (!(given instanceof JsonPrimitive p) || !p.isNumber() || p.getAsDouble() != Math.rint(p.getAsDouble()) || p.getAsDouble() < 1
				|| p.getAsDouble() > 8) {
			throw new IllegalArgumentException("bits must be a whole number from 1 to 8, not " + text);
		}
		return p.getAsInt();
	}

	/** A lever row's label, "in" unless given: 1 to 8 lowercase letters, digits or _, starting with a letter. */
	static String label(JsonObject options) {
		JsonElement given = options.get("label");
		if (given == null) {
			return "in";
		}
		String label = given.isJsonPrimitive() ? given.getAsString() : "";
		if (!label.matches("[a-z][a-z0-9_]{0,7}")) {
			throw new IllegalArgumentException("label must be 1 to 8 lowercase letters, digits or _, starting with a letter");
		}
		return label;
	}
}
