package io.palmisano.aibuilder.core.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import org.junit.jupiter.api.Test;

class ShapesTest {
	static final BlockSpec STONE = BlockSpec.of("minecraft:stone");

	static Set<Pos> positions(List<LocalBlock> blocks) {
		return blocks.stream().map(LocalBlock::pos).collect(Collectors.toSet());
	}

	@Test
	void boxCornersInAnyOrder() {
		Box box = Box.of(3, 0, 3, 1, 2, 1);
		assertEquals(new Pos(1, 0, 1), box.min());
		assertEquals(new Pos(3, 2, 3), box.max());
		assertEquals(27, box.volume());
	}

	@Test
	void solidFillCoversTheBox() {
		List<LocalBlock> fill = Shapes.fill(Box.of(0, 0, 0, 1, 1, 1), STONE, FillMode.SOLID);
		assertEquals(8, fill.size());
		assertTrue(fill.stream().allMatch(b -> b.block().equals(STONE)));
	}

	@Test
	void hollowFillMakesAShellAndClearsTheInside() {
		List<LocalBlock> fill = Shapes.fill(Box.of(0, 0, 0, 2, 2, 2), STONE, FillMode.HOLLOW);
		assertEquals(26, fill.stream().filter(b -> b.block().equals(STONE)).count());
		assertEquals(List.of(new Pos(1, 1, 1)),
				fill.stream().filter(b -> b.block().id().equals("minecraft:air")).map(LocalBlock::pos).toList());
	}

	@Test
	void outlineLeavesTheInsideAlone() {
		List<LocalBlock> fill = Shapes.fill(Box.of(0, 0, 0, 2, 2, 2), STONE, FillMode.OUTLINE);
		assertEquals(26, fill.size());
		assertFalse(positions(fill).contains(new Pos(1, 1, 1)));
	}

	@Test
	void replaceCarriesTheFilter() {
		List<LocalBlock> fill = Shapes.replace(Box.of(0, 0, 0, 1, 0, 0), STONE, "#minecraft:dirt");
		assertEquals(2, fill.size());
		assertTrue(fill.stream().allMatch(b -> "#minecraft:dirt".equals(b.onlyReplacing())));
	}

	@Test
	void straightLineIncludesBothEnds() {
		List<LocalBlock> line = Shapes.line(new Pos(0, 0, 0), new Pos(3, 0, 0), STONE);
		assertEquals(Set.of(new Pos(0, 0, 0), new Pos(1, 0, 0), new Pos(2, 0, 0), new Pos(3, 0, 0)), positions(line));
		assertEquals(4, line.size());
	}

	@Test
	void diagonalLineStepsEvenly() {
		assertEquals(List.of(new Pos(0, 0, 0), new Pos(1, 1, 1), new Pos(2, 2, 2)),
				Shapes.line(new Pos(0, 0, 0), new Pos(2, 2, 2), STONE).stream().map(LocalBlock::pos).toList());
	}

	@Test
	void wallFollowsThePolylineWithoutDuplicateCorners() {
		List<LocalBlock> wall = Shapes.wall(List.of(new int[] {0, 0}, new int[] {2, 0}, new int[] {2, 2}), 1, 2, STONE, 1);
		Set<Pos> expected = new HashSet<>();
		for (int y = 1; y <= 2; y++) {
			expected.addAll(List.of(new Pos(0, y, 0), new Pos(1, y, 0), new Pos(2, y, 0), new Pos(2, y, 1), new Pos(2, y, 2)));
		}
		assertEquals(expected, positions(wall));
		assertEquals(10, wall.size());
	}

	@Test
	void thickWallGrowsToTheRightOfTravel() {
		List<LocalBlock> wall = Shapes.wall(List.of(new int[] {0, 0}, new int[] {2, 0}), 1, 1, STONE, 2);
		assertEquals(Set.of(new Pos(0, 1, 0), new Pos(1, 1, 0), new Pos(2, 1, 0),
				new Pos(0, 1, -1), new Pos(1, 1, -1), new Pos(2, 1, -1)), positions(wall));
	}
}
