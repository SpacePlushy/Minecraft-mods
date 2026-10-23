package io.palmisano.aibuilder.core.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.palmisano.aibuilder.core.frame.Pos;
import org.junit.jupiter.api.Test;

/** A row of wires (spec v2 §5.4): each along z to a turning row of its own, along x, then along z, none touching. */
class WireRowsTest {
	/** Right shifts turn rightmost first and left shifts leftmost first, on rows 2 apart from 2 past the outputs. */
	@Test
	void rowsTurnInAnOrderThatNeverCrosses() {
		List<Pos> from = List.of(new Pos(0, 1, 0), new Pos(4, 1, 0), new Pos(8, 1, 0), new Pos(12, 1, 0), new Pos(16, 1, 0));
		List<Pos> to = List.of(new Pos(-4, 3, 12), new Pos(6, 3, 12), new Pos(10, 3, 12), new Pos(20, 3, 12), new Pos(22, 3, 12));
		assertEquals(List.of(2, 8, 6, 4, 2), WireRows.turns(from, to));
		List<List<Pos>> wires = new ArrayList<>();
		for (List<Pos> points : WireRows.points(from, to)) {
			wires.add(WirePath.trace(points));
		}
		for (int i = 0; i < wires.size(); i++) {
			assertEquals(from.get(i), wires.get(i).getFirst());
			assertEquals(to.get(i), wires.get(i).getLast());
			for (int j = i + 1; j < wires.size(); j++) {
				for (Pos a : wires.get(i)) {
					for (Pos b : wires.get(j)) {
						boolean apart = Math.abs(a.x() - b.x()) >= 2 || Math.abs(a.z() - b.z()) >= 2 || Math.abs(a.y() - b.y()) >= 2;
						assertTrue(apart, "wires " + i + " and " + j + " come together at " + a + " and " + b);
					}
				}
			}
		}
		// Back along z works the same way; ports given out of order would cross.
		assertEquals(List.of(-2, -2), WireRows.turns(List.of(new Pos(0, 1, 0), new Pos(4, 1, 0)), List.of(new Pos(-2, 1, -6), new Pos(8, 1, -6))));
		IllegalArgumentException crossing = assertThrows(IllegalArgumentException.class,
				() -> WireRows.turns(List.of(new Pos(0, 1, 0), new Pos(4, 1, 0)), List.of(new Pos(8, 1, 10), new Pos(2, 1, 10))));
		assertEquals("the wires from (0, 1, 0) and (4, 1, 0) would cross: give to in the same order as from", crossing.getMessage());
	}

	@Test
	void tooLittleRoomBetweenRowsIsRefused() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> WireRows.turns(
				List.of(new Pos(0, 1, 0), new Pos(4, 1, 0), new Pos(8, 1, 0)), List.of(new Pos(2, 1, 6), new Pos(6, 1, 6), new Pos(10, 1, 6))));
		assertEquals("the wires that shift sideways turn on rows 2 apart, 3 of them here: the inputs must be at least 8 blocks from the"
				+ " outputs along z, not 6", e.getMessage());
		IllegalArgumentException level = assertThrows(IllegalArgumentException.class, () -> WireRows.turns(
				List.of(new Pos(0, 1, 0), new Pos(4, 1, 1)), List.of(new Pos(0, 1, 9), new Pos(4, 1, 9))));
		assertEquals("a row of wires starts from ports in a row along x (the same y and z): (4, 1, 1) isn't in line with (0, 1, 0)",
				level.getMessage());
	}
}
