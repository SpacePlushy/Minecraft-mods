package io.palmisano.aibuilder.core.gadgets;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** The library place_gadget makes from (spec v2 §5.3): each kind and the options it takes. Pure. */
public final class Gadgets {
	public static final List<String> KINDS = List.of("lever_row", "lamp_row", "adder", "digit", "clock", "pulse", "toggle", "latch", "counter",
			"piston_door");
	/** The options each kind takes. */
	private static final Map<String, Set<String>> OPTIONS = Map.ofEntries(Map.entry("lever_row", Set.of("bits", "label")),
			Map.entry("lamp_row", Set.of("bits")), Map.entry("adder", Set.of("bits")), Map.entry("digit", Set.of()),
			Map.entry("clock", Set.of("period")), Map.entry("pulse", Set.of("length")), Map.entry("toggle", Set.of()),
			Map.entry("latch", Set.of("bits")), Map.entry("counter", Set.of("bits")), Map.entry("piston_door", Set.of()));

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
			case "clock" -> Clock.make(period(options));
			case "pulse" -> Pulse.make(length(options));
			case "toggle" -> Toggle.make();
			case "latch" -> Latch.make(bits(options, 4));
			case "counter" -> Counter.make(bits(options, 4));
			case "piston_door" -> PistonDoor.make();
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
			case "clock" -> "a clock (every " + period(options) + " ticks)";
			case "pulse" -> "a pulse maker (" + length(options) + " ticks)";
			case "toggle" -> "a toggle";
			case "latch" -> "a latch (" + count + ")";
			case "counter" -> "a counter (" + count + ")";
			case "piston_door" -> "a 2 × 2 piston door";
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

	/** A clock's period in ticks, 20 unless given: 12 to 64, a multiple of 4 (see {@link Clock}). */
	static int period(JsonObject options) {
		return whole(options, "period", 12, 64, 4, 20);
	}

	/** A pulse maker's pulse in ticks, 4 unless given: 2 to 8, even (a repeater's delay goes 2 ticks a step). */
	static int length(JsonObject options) {
		return whole(options, "length", 2, 8, 2, 4);
	}

	/** Option {@code name}, a whole number from {@code min} to {@code max} in steps of {@code step}, or {@code otherwise}. */
	static int whole(JsonObject options, String name, int min, int max, int step, int otherwise) {
		JsonElement given = options.get(name);
		if (given == null) {
			return otherwise;
		}
		String text = given.toString();
		if (!(given instanceof JsonPrimitive p) || !p.isNumber() || p.getAsDouble() != Math.rint(p.getAsDouble()) || p.getAsDouble() < min
				|| p.getAsDouble() > max || p.getAsInt() % step != 0) {
			throw new IllegalArgumentException(name + " must be a whole number from " + min + " to " + max + (step > 1 ? ", a multiple of " + step : "")
					+ ", not " + text);
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
