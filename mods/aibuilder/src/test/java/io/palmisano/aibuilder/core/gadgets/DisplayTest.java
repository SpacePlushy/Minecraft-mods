package io.palmisano.aibuilder.core.gadgets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;
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

	/** The number's logic for every value 0 to 31: its tens array's products and its ones array's, chained, read back right. */
	@Test
	void aNumberShowsEveryValueInLogic() {
		Map<String, Pos> marks = Number.make(5).marks();
		List<Logic.Product> tens = Logic.minimize(4, Number.tensOn(4), Set.of());
		List<Logic.Product> ones = Logic.minimize(4, Number.onesOn(), Number.onesDontCare());
		for (int v = 0; v < 32; v++) {
			int value = v;
			int w = v >> 1;
			Set<Pos> lit = new HashSet<>();
			int q = 0;
			for (int out = 0; out < 10; out++) {
				int o = out;
				if (tens.stream().anyMatch(p -> p.outputs().contains(o) && p.covers(w))) {
					if (out < 7) {
						lit.add(marks.get("tens segment " + "abcdefg".charAt(out)));
					} else {
						q |= 1 << (out - 7);
					}
				}
			}
			// The ones array's inputs: n0, then q's bits high to low (the order its U-turns nest in).
			int u = (value & 1) | (q >> 2 & 1) << 1 | (q >> 1 & 1) << 2 | (q & 1) << 3;
			for (int s = 0; s < 7; s++) {
				int segment = s;
				if (ones.stream().anyMatch(p -> p.outputs().contains(segment) && p.covers(u))) {
					lit.add(marks.get("ones segment " + "abcdefg".charAt(s)));
				}
			}
			assertEquals(String.valueOf(v), Display.number(marks, lit::contains), "value " + v);
		}
	}

	/** Dark tens are blank (9 reads "9"), a tens lit as 0 reads as it shows; a face that makes no digit reads "?". */
	@Test
	void aNumberReadsItsTwoFaces() {
		Map<String, Pos> marks = Number.make(5).marks();
		assertEquals("?", Display.number(marks, p -> false), "no ones");
		assertEquals("08", Display.number(marks, p -> !p.equals(marks.get("tens segment g"))), "the tens as it shows");
		Map<String, Pos> small = Number.make(2).marks();
		assertEquals("8", Display.number(small, p -> true));
	}

	/** Every inner wire of every size of number keeps to what WireRules lets a wire touch: only the buffers at its two ends. */
	@Test
	void aNumbersInnerWiresTouchNothing() {
		for (int bits = 1; bits <= 5; bits++) {
			Gadget number = Number.make(bits);
			Map<Pos, io.palmisano.aibuilder.core.wire.Cell> cells = new HashMap<>();
			for (LocalBlock b : number.blocks()) {
				cells.put(b.pos(), cell(b.block().id()));
			}
			io.palmisano.aibuilder.core.shapes.Box box = io.palmisano.aibuilder.core.shapes.Box.of(0, 0, 0, number.size().x() - 1,
					number.size().y() - 1, number.size().z() - 1);
			List<Number.Wire> wires = Number.wires(bits);
			assertEquals(bits >= 4 ? bits + 3 : 0, wires.size(), bits + " bits");
			for (Number.Wire wire : wires) {
				Map<Pos, io.palmisano.aibuilder.core.wire.Cell> around = new HashMap<>(cells);
				wire.spots().forEach(p -> around.put(p, io.palmisano.aibuilder.core.wire.Cell.FREE));
				for (Pos p : io.palmisano.aibuilder.core.wire.WireRules.needed(wire.spots())) {
					around.putIfAbsent(p, io.palmisano.aibuilder.core.wire.Cell.FREE);
				}
				io.palmisano.aibuilder.core.wire.WireRules.supports(wire.spots(), around, wire.ends(), box,
						io.palmisano.aibuilder.core.place.ProtectionMask.none());
			}
		}
	}

	private static io.palmisano.aibuilder.core.wire.Cell cell(String id) {
		return switch (id) {
			case "air" -> io.palmisano.aibuilder.core.wire.Cell.FREE;
			case "redstone_wire" -> io.palmisano.aibuilder.core.wire.Cell.DUST;
			case "repeater", "comparator", "redstone_wall_torch", "redstone_lamp" -> io.palmisano.aibuilder.core.wire.Cell.COMPONENT;
			default -> io.palmisano.aibuilder.core.wire.Cell.SOLID;
		};
	}
}
