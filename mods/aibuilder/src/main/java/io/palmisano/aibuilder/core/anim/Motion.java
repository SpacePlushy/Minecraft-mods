package io.palmisano.aibuilder.core.anim;

import java.util.Locale;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.palmisano.aibuilder.core.Tunables;

/**
 * How a moving part moves (spec v2 §4.2): one of three, each a cycle of {@code period} ticks that {@code phase} (0
 * to 1) shifts. Axes and directions are the relative words. A bad value is refused with the reason Claude is told.
 */
public sealed interface Motion permits Motion.Swing, Motion.Spin, Motion.Bob {
	Set<String> WORDS = Set.of("forward", "back", "left", "right", "up", "down");
	String ONE_OF = "a motion is one of swing, spin or bob, given as {\"swing\": {…}}, {\"spin\": {…}} or {\"bob\": {…}}";

	/** Back and forth between two angles about an axis, smoothly: {@code from} at the start, {@code to} half a period on. */
	record Swing(String axis, double from, double to, int period, double phase) implements Motion {
		public Swing {
			axis = word(axis, "axis");
			requireDegrees(from, "from");
			requireDegrees(to, "to");
			requirePeriod(period, false);
			requirePhase(phase);
			double step = Poses.swingStep(from, to, period);
			if (step >= Tunables.STEP_DEGREES_MAX) {
				throw new IllegalArgumentException(String.format(Locale.ROOT, "too fast to draw: this swing turns up to %.0f° between two poses (one every"
						+ " %d ticks), and the game glides the short way round; at most %.0f° (a longer period, or a smaller swing)", step,
						Tunables.ANIMATION_STEP_TICKS, Tunables.STEP_DEGREES_MAX));
			}
		}
	}

	/** A full turn about an axis each {@code period} ticks; a negative period turns the other way. */
	record Spin(String axis, int period, double phase) implements Motion {
		public Spin {
			axis = word(axis, "axis");
			requirePeriod(period, true);
			requirePhase(phase);
		}
	}

	/** Back and forth along a direction, out to {@code distance} blocks half a period on, smoothly. */
	record Bob(String direction, double distance, int period, double phase) implements Motion {
		public Bob {
			direction = word(direction, "direction");
			if (!(distance >= 0 && distance <= Tunables.MOTION_DISTANCE_MAX)) {
				throw new IllegalArgumentException("distance is blocks from 0 to " + (int) Tunables.MOTION_DISTANCE_MAX + ", not " + distance);
			}
			requirePeriod(period, false);
			requirePhase(phase);
		}
	}

	/** The motion as {@link #parse} reads it: how the world saves it. */
	default JsonObject toJson() {
		JsonObject m = new JsonObject();
		String kind = switch (this) {
			case Swing s -> {
				m.addProperty("axis", s.axis());
				m.addProperty("from", s.from());
				m.addProperty("to", s.to());
				m.addProperty("period", s.period());
				m.addProperty("phase", s.phase());
				yield "swing";
			}
			case Spin s -> {
				m.addProperty("axis", s.axis());
				m.addProperty("period", s.period());
				m.addProperty("phase", s.phase());
				yield "spin";
			}
			case Bob b -> {
				m.addProperty("direction", b.direction());
				m.addProperty("distance", b.distance());
				m.addProperty("period", b.period());
				m.addProperty("phase", b.phase());
				yield "bob";
			}
		};
		JsonObject motion = new JsonObject();
		motion.add(kind, m);
		return motion;
	}

	/** {@code {"swing": {…}}}, {@code {"spin": {…}}} or {@code {"bob": {…}}}; phase is optional (0). */
	static Motion parse(JsonObject motion) {
		if (motion == null || motion.size() != 1) {
			throw new IllegalArgumentException(ONE_OF);
		}
		String kind = motion.keySet().iterator().next();
		if (!(motion.get(kind) instanceof JsonObject m)) {
			throw new IllegalArgumentException(ONE_OF);
		}
		return switch (kind) {
			case "swing" -> new Swing(string(m, "axis"), number(m, "from"), number(m, "to"), whole(m, "period"), phaseOf(m));
			case "spin" -> new Spin(string(m, "axis"), whole(m, "period"), phaseOf(m));
			case "bob" -> new Bob(string(m, "direction"), number(m, "distance"), whole(m, "period"), phaseOf(m));
			default -> throw new IllegalArgumentException(ONE_OF);
		};
	}

	private static String word(String value, String name) {
		String word = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
		if (!WORDS.contains(word)) {
			throw new IllegalArgumentException(name + " must be forward, back, left, right, up or down, not \"" + value + "\"");
		}
		return word;
	}

	private static void requireDegrees(double value, String name) {
		if (!(value >= -Tunables.MOTION_ANGLE_MAX && value <= Tunables.MOTION_ANGLE_MAX)) {
			throw new IllegalArgumentException(name + " is degrees from -180 to 180, not " + value);
		}
	}

	private static void requirePeriod(int period, boolean eitherWay) {
		int size = Math.abs(period);
		if (size < Tunables.MOTION_PERIOD_MIN || size > Tunables.MOTION_PERIOD_MAX || (period < 0 && !eitherWay)) {
			throw new IllegalArgumentException(periodRule(eitherWay) + ", not " + period);
		}
	}

	private static String periodRule(boolean eitherWay) {
		return "period must be a whole number of ticks from " + Tunables.MOTION_PERIOD_MIN + " to "
				+ String.format(Locale.ROOT, "%,d", Tunables.MOTION_PERIOD_MAX) + (eitherWay ? " (negative to turn the other way)" : "");
	}

	private static void requirePhase(double phase) {
		if (!(phase >= 0 && phase <= 1)) {
			throw new IllegalArgumentException("phase is a fraction of the cycle from 0 to 1, not " + phase);
		}
	}

	private static String string(JsonObject m, String key) {
		JsonElement e = m.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(key + " is missing: forward, back, left, right, up or down");
		}
		return e.getAsString();
	}

	private static double number(JsonObject m, String key) {
		JsonElement e = m.get(key);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !Double.isFinite(e.getAsDouble())) {
			throw new IllegalArgumentException(key + " must be a number");
		}
		return e.getAsDouble();
	}

	private static int whole(JsonObject m, String key) {
		JsonElement e = m.get(key);
		boolean whole = e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && e.getAsDouble() == Math.rint(e.getAsDouble())
				&& Math.abs(e.getAsDouble()) <= Integer.MAX_VALUE;
		if (!whole) {
			throw new IllegalArgumentException(periodRule(true) + ": a whole number");
		}
		return (int) e.getAsDouble();
	}

	private static double phaseOf(JsonObject m) {
		return m.has("phase") ? number(m, "phase") : 0;
	}
}
