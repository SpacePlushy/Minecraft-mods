package io.palmisano.aibuilder.core.gadgets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.palmisano.aibuilder.core.frame.Pos;
import org.junit.jupiter.api.Test;

/** A digit's face and the lines that drive it (spec v2 §5.3). */
class DisplayTest {
	/** For each code 0 to 9, the segments the decoder's logic lights read back as that digit. */
	@Test
	void eachDigitLightsItsSegments() {
		Gadget digit = Digit.make();
		Set<Integer> dontCare = Set.of(10, 11, 12, 13, 14, 15);
		List<Logic.Product> products = Logic.minimize(4, Digit.onSets(), dontCare);
		for (int v = 0; v < 10; v++) {
			Set<Pos> lit = new HashSet<>();
			for (int s = 0; s < 7; s++) {
				int segment = s;
				int value = v;
				if (products.stream().anyMatch(p -> p.outputs().contains(segment) && p.covers(value))) {
					lit.add(digit.marks().get("segment " + "abcdefg".charAt(s)));
				}
			}
			assertEquals(String.valueOf(v), Display.read(digit.marks(), lit::contains), "code " + v);
		}
	}

	@Test
	void aPatternThatIsntADigitReadsAsAQuestionMark() {
		Map<String, Pos> marks = Digit.make().marks();
		assertEquals("?", Display.read(marks, p -> false));
		assertEquals("?", Display.read(marks, p -> p.equals(marks.get("segment a"))));
	}

	/** No two of the seven lines share a spot, sit side by side, or step diagonally to each other: none can touch. */
	@Test
	void theBundleLinesNeverTouch() {
		List<List<Pos>> paths = Digit.paths();
		assertEquals(7, paths.size());
		for (int i = 0; i < paths.size(); i++) {
			for (int j = i + 1; j < paths.size(); j++) {
				for (Pos a : paths.get(i)) {
					for (Pos b : paths.get(j)) {
						int dx = Math.abs(a.x() - b.x());
						int dy = Math.abs(a.y() - b.y());
						int dz = Math.abs(a.z() - b.z());
						assertFalse(dx + dy + dz <= 1, "lines " + i + " and " + j + " touch at " + a + " and " + b);
						assertFalse(dy == 1 && dx + dz == 1, "lines " + i + " and " + j + " step to each other at " + a + " and " + b);
						assertFalse(dx == 0 && dz == 0 && b.y() == a.y() - 1, "line " + j + " stands under line " + i + " at " + b);
						assertFalse(dx == 0 && dz == 0 && a.y() == b.y() - 1, "line " + i + " stands under line " + j + " at " + a);
					}
				}
			}
		}
		assertTrue(paths.stream().allMatch(p -> p.size() > 10), "a line that doesn't reach the face");
	}
}
