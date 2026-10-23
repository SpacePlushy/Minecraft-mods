package io.palmisano.aibuilder.core.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;
import org.junit.jupiter.api.Test;

/** A wire's way (spec v2 §5.4): the spots its dust takes and where it needs repeaters. */
class WirePathTest {
	/** From each point to the next a wire goes along z, then along x, a level a block from the leg's start until it's at height. */
	@Test
	void aPathRunsZThenXAndClimbsAtTheStart() {
		assertEquals(List.of(new Pos(0, 1, 0), new Pos(0, 2, 1), new Pos(0, 3, 2), new Pos(0, 3, 3), new Pos(0, 3, 4), new Pos(1, 3, 4),
				new Pos(2, 3, 4), new Pos(3, 3, 4)), WirePath.trace(List.of(new Pos(0, 1, 0), new Pos(3, 3, 4))));
		// Down through a via point, then on: each leg changes height from its own start.
		assertEquals(List.of(new Pos(0, 3, 0), new Pos(-1, 2, 0), new Pos(-2, 2, 0), new Pos(-2, 3, -1), new Pos(-2, 3, -2)),
				WirePath.trace(List.of(new Pos(0, 3, 0), new Pos(-2, 2, 0), new Pos(-2, 3, -2))));
		IllegalArgumentException tooSteep = assertThrows(IllegalArgumentException.class,
				() -> WirePath.trace(List.of(new Pos(0, 1, 0), new Pos(0, 5, 2))));
		assertEquals("from (0, 1, 0) to (0, 5, 2) the wire would climb 4 levels in 2 blocks: it climbs a level a block, so give it room"
				+ " (a via point further off)", tooSteep.getMessage());
		IllegalArgumentException same = assertThrows(IllegalArgumentException.class,
				() -> WirePath.trace(List.of(new Pos(0, 1, 0), new Pos(0, 1, 0))));
		assertEquals("the wire's points (0, 1, 0) and (0, 1, 0) are the same spot", same.getMessage());
	}

	/**
	 * The signal runs out after 15 dust: a repeater takes the last straight flat spot before it would, never a turn or a
	 * step, nor either end (a port's dust); it faces back at the signal, so it carries it on.
	 */
	@Test
	void repeatersComeEvery15OnStraightFlatStretches() {
		List<Pos> straight = WirePath.trace(List.of(new Pos(0, 1, 0), new Pos(0, 1, 40)));
		assertEquals(Set.of(14, 29), WirePath.repeaters(straight));
		// A turn at the 15th spot: the repeater comes one before it.
		List<Pos> turning = WirePath.trace(List.of(new Pos(0, 1, 0), new Pos(10, 1, 14)));
		assertEquals(Set.of(13), WirePath.repeaters(turning));
		assertEquals(Set.of(), WirePath.repeaters(WirePath.trace(List.of(new Pos(0, 1, 0), new Pos(0, 1, 14)))), "15 spots reach the end at 1");
		// A climb of 16 has nowhere to put one.
		IllegalArgumentException climb = assertThrows(IllegalArgumentException.class,
				() -> WirePath.repeaters(WirePath.trace(List.of(new Pos(0, 1, 0), new Pos(0, 17, 16), new Pos(0, 17, 20)))));
		assertEquals("the signal would run out at (0, 16, 15), with no straight, level stretch before it since (0, 1, 0) for a repeater: give"
				+ " the wire a level run (via points)", climb.getMessage());
		List<LocalBlock> blocks = WirePath.blocks(turning, Set.of(13), Set.of(new Pos(0, 0, 0)));
		LocalBlock repeater = blocks.stream().filter(b -> b.pos().equals(new Pos(0, 1, 13))).findFirst().orElseThrow();
		assertEquals("repeater", repeater.block().id());
		assertEquals(Map.of("facing", "back", "delay", "1"), repeater.block().state());
		assertEquals(26, blocks.size(), "25 spots and a support");
	}
}
