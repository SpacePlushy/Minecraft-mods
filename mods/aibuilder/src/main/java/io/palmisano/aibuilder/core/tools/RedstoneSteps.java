package io.palmisano.aibuilder.core.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.Box;

/**
 * test_redstone's steps (spec v2 §5.2), read from Claude's list and held to their limits: at most
 * {@link Tunables#TEST_STEPS_MAX} steps, {@link Tunables#TEST_WAIT_MAX} ticks waited in all,
 * {@link Tunables#TEST_READ_MAX} spots a read, every spot inside the allowed box. A bad step is refused with the
 * reason Claude is told.
 */
public final class RedstoneSteps {
	private static final String ONE_OF = "a step is one of set, press, wait, read or show: {\"set\": [x, y, z], \"on\": true}, {\"press\": [x, y, z]},"
			+ " {\"wait\": ticks}, {\"read\": [[x, y, z], …]} or {\"show\": true}";

	public sealed interface Step permits Set, Press, Wait, Read, Show {
	}

	/** Flip the build's lever at {@code lever} on or off. */
	public record Set(Pos lever, boolean on) implements Step {
	}

	/** Press the build's button at {@code button}. */
	public record Press(Pos button) implements Step {
	}

	/** Let the machine run for {@code ticks}. */
	public record Wait(int ticks) implements Step {
	}

	/** Say what's at each spot, with its state. */
	public record Read(List<Pos> spots) implements Step {
		public Read {
			spots = List.copyOf(spots);
		}
	}

	/** Say what each digit and number of the build shows; test_redstone fills in which they are. */
	public record Show(List<Shown> displays) implements Step {
		public Show {
			displays = List.copyOf(displays);
		}
	}

	/** A display to read: "digit" or "number", its front-left-bottom block, and its segments' centre lamps as placed. */
	public record Shown(String what, Pos at, Map<String, Pos> marks) {
	}

	private RedstoneSteps() {
	}

	public static List<Step> parse(JsonArray steps, Box allowed) {
		if (steps.isEmpty()) {
			throw new IllegalArgumentException("steps needs at least one step");
		}
		if (steps.size() > Tunables.TEST_STEPS_MAX) {
			throw new IllegalArgumentException("a test is at most " + Tunables.TEST_STEPS_MAX + " steps, not " + steps.size());
		}
		List<Step> out = new ArrayList<>(steps.size());
		int waited = 0;
		for (JsonElement element : steps) {
			if (!(element instanceof JsonObject step)) {
				throw new IllegalArgumentException(ONE_OF);
			}
			Step parsed;
			if (step.has("set")) {
				if (!(step.get("on") instanceof JsonElement on) || !on.isJsonPrimitive() || !on.getAsJsonPrimitive().isBoolean()) {
					throw new IllegalArgumentException("set needs on: true or false");
				}
				parsed = new Set(spot(step.get("set"), allowed), on.getAsBoolean());
			} else if (step.has("press")) {
				parsed = new Press(spot(step.get("press"), allowed));
			} else if (step.has("wait")) {
				JsonElement ticks = step.get("wait");
				if (!ticks.isJsonPrimitive() || !ticks.getAsJsonPrimitive().isNumber() || ticks.getAsDouble() != Math.rint(ticks.getAsDouble())
						|| ticks.getAsDouble() < 1 || ticks.getAsDouble() > Tunables.TEST_WAIT_MAX) {
					throw new IllegalArgumentException("wait is a whole number of ticks from 1 to " + Tunables.TEST_WAIT_MAX + ", not " + ticks);
				}
				parsed = new Wait(ticks.getAsInt());
				waited += ticks.getAsInt();
			} else if (step.has("read")) {
				if (!(step.get("read") instanceof JsonArray spots) || spots.isEmpty()) {
					throw new IllegalArgumentException("read is a list of spots, [[x, y, z], …]");
				}
				if (spots.size() > Tunables.TEST_READ_MAX) {
					throw new IllegalArgumentException("a read is at most " + Tunables.TEST_READ_MAX + " spots, not " + spots.size());
				}
				List<Pos> read = new ArrayList<>(spots.size());
				spots.forEach(spot -> read.add(spot(spot, allowed)));
				parsed = new Read(read);
			} else if (step.has("show")) {
				JsonElement show = step.get("show");
				if (!show.isJsonPrimitive() || !show.getAsJsonPrimitive().isBoolean() || !show.getAsBoolean()) {
					throw new IllegalArgumentException("show takes true: {\"show\": true}");
				}
				parsed = new Show(List.of());
			} else {
				throw new IllegalArgumentException(ONE_OF);
			}
			out.add(parsed);
		}
		if (waited > Tunables.TEST_WAIT_MAX) {
			throw new IllegalArgumentException("a test waits at most " + Tunables.TEST_WAIT_MAX + " ticks in all, not " + waited);
		}
		return out;
	}

	private static Pos spot(JsonElement element, Box allowed) {
		if (!(element instanceof JsonArray xyz) || xyz.size() != 3) {
			throw new IllegalArgumentException("a spot is [x, y, z], not " + element);
		}
		int[] c = new int[3];
		for (int i = 0; i < 3; i++) {
			JsonElement v = xyz.get(i);
			if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() || v.getAsDouble() != Math.rint(v.getAsDouble())
					|| Math.abs(v.getAsDouble()) > Tunables.ARGUMENT_LIMIT) {
				throw new IllegalArgumentException("a spot is three whole numbers, not " + element);
			}
			c[i] = v.getAsInt();
		}
		Pos pos = new Pos(c[0], c[1], c[2]);
		if (!allowed.contains(pos)) {
			throw new IllegalArgumentException("(" + c[0] + ", " + c[1] + ", " + c[2] + ") is outside the allowed box");
		}
		return pos;
	}
}
