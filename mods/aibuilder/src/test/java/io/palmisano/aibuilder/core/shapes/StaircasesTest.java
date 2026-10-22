package io.palmisano.aibuilder.core.shapes;

import static io.palmisano.aibuilder.core.shapes.RoofsTest.STAIRS;
import static io.palmisano.aibuilder.core.shapes.RoofsTest.assertStair;
import static io.palmisano.aibuilder.core.shapes.RoofsTest.byPos;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import org.junit.jupiter.api.Test;

class StaircasesTest {
	@Test
	void straightStairsClimbTowardTheirDirection() {
		Map<Pos, BlockSpec> stairs = byPos(Staircases.straight(new Pos(0, 0, 0), "forward", 3, 1, STAIRS));
		assertEquals(3, stairs.size());
		assertStair(stairs, new Pos(0, 0, 0), "forward");
		assertStair(stairs, new Pos(0, 1, 1), "forward");
		assertStair(stairs, new Pos(0, 2, 2), "forward");
	}

	@Test
	void wideStairsGrowToTheRight() {
		Map<Pos, BlockSpec> stairs = byPos(Staircases.straight(new Pos(0, 0, 0), "forward", 3, 2, STAIRS));
		assertEquals(6, stairs.size());
		assertStair(stairs, new Pos(1, 0, 0), "forward");
		assertStair(stairs, new Pos(1, 1, 1), "forward");
		assertStair(stairs, new Pos(1, 2, 2), "forward");
	}

	@Test
	void spiralGoesRoundAndUp() {
		List<LocalBlock> spiral = Staircases.spiral(new Pos(0, 0, 0), 1, 0, 7, STAIRS, true);
		assertEquals(8, spiral.size());
		Map<Pos, BlockSpec> steps = byPos(spiral);
		assertStair(steps, new Pos(0, 0, -1), "left");
		assertStair(steps, new Pos(-1, 1, -1), "forward");
		assertStair(steps, new Pos(-1, 2, 0), "forward");
		for (int i = 0; i < spiral.size(); i++) {
			assertEquals(i, spiral.get(i).pos().y(), "one step higher each time");
		}
		Set<Pos> cells = spiral.stream().map(b -> new Pos(b.pos().x(), 0, b.pos().z())).collect(Collectors.toSet());
		assertEquals(8, cells.size(), "every ring cell once");
		assertTrue(cells.stream().noneMatch(c -> c.x() == 0 && c.z() == 0), "the centre column is left for a pillar");
	}
}
