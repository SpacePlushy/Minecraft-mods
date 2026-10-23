package io.palmisano.aibuilder.core.gadgets;

import java.util.Map;
import java.util.function.Predicate;

import io.palmisano.aibuilder.core.frame.Pos;

/** What a digit shows (spec v2 §5.3), read off its segments' centre lamps. Pure. */
public final class Display {
	private Display() {
	}

	/** The digit its lit segments make, "?" if they make none; {@code marks} are the digit's named spots, placed. */
	public static String read(Map<String, Pos> marks, Predicate<Pos> lit) {
		String digit = face(marks, "", lit);
		return digit.isEmpty() ? "?" : digit;
	}

	/** What a number shows: its tens (left out while dark, as under 10), then its ones; a face that makes no digit reads "?". */
	public static String number(Map<String, Pos> marks, Predicate<Pos> lit) {
		String ones = face(marks, "ones ", lit);
		String tens = marks.containsKey("tens segment a") ? face(marks, "tens ", lit) : "";
		return tens + (ones.isEmpty() ? "?" : ones);
	}

	/** The digit a face's lit segments make: "" if none is lit, "?" if they make no digit. */
	private static String face(Map<String, Pos> marks, String prefix, Predicate<Pos> lit) {
		StringBuilder on = new StringBuilder();
		for (char segment : "abcdefg".toCharArray()) {
			Pos centre = marks.get(prefix + "segment " + segment);
			if (centre != null && lit.test(centre)) {
				on.append(segment);
			}
		}
		if (on.isEmpty()) {
			return "";
		}
		for (int v = 0; v < Digit.LIT.length; v++) {
			if (Digit.LIT[v].contentEquals(on)) {
				return String.valueOf(v);
			}
		}
		return "?";
	}
}
