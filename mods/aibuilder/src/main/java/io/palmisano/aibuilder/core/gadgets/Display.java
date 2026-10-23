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
		StringBuilder on = new StringBuilder();
		for (char segment : "abcdefg".toCharArray()) {
			Pos centre = marks.get("segment " + segment);
			if (centre != null && lit.test(centre)) {
				on.append(segment);
			}
		}
		for (int v = 0; v < Digit.LIT.length; v++) {
			if (Digit.LIT[v].contentEquals(on)) {
				return String.valueOf(v);
			}
		}
		return "?";
	}
}
