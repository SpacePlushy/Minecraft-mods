package io.palmisano.aibuilder.core.shapes;

import static io.palmisano.aibuilder.core.shapes.ShapesTest.STONE;
import static io.palmisano.aibuilder.core.shapes.ShapesTest.positions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.palmisano.aibuilder.core.frame.Pos;
import org.junit.jupiter.api.Test;

class RoundShapesTest {
	/** Whether every block can be reached from every other through blocks touching at a face, edge or corner. */
	private static boolean connected(Set<Pos> blocks) {
		Set<Pos> seen = new HashSet<>();
		Deque<Pos> todo = new ArrayDeque<>(List.of(blocks.iterator().next()));
		while (!todo.isEmpty()) {
			Pos p = todo.pop();
			if (!blocks.contains(p) || !seen.add(p)) {
				continue;
			}
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						todo.push(new Pos(p.x() + dx, p.y() + dy, p.z() + dz));
					}
				}
			}
		}
		return seen.size() == blocks.size();
	}

	private static boolean mirroredAboutX(Set<Pos> blocks, int middleTimesTwo) {
		return blocks.stream().allMatch(p -> blocks.contains(new Pos(middleTimesTwo - p.x(), p.y(), p.z())));
	}

	@Test
	void cylinderOfRadiusOneIsAThreeByThreeColumn() {
		assertEquals(18, Shapes.cylinder(new Pos(0, 0, 0), 1, 2, STONE, Axis.Y, false).size());
	}

	@Test
	void cylinderRadiusTwoDropsTheCorners() {
		Set<Pos> disc = positions(Shapes.cylinder(new Pos(0, 0, 0), 2, 1, STONE, Axis.Y, false));
		assertEquals(21, disc.size());
		assertTrue(disc.contains(new Pos(2, 0, 1)));
		assertFalse(disc.contains(new Pos(2, 0, 2)));
	}

	@Test
	void hollowCylinderKeepsTheRing() {
		assertEquals(12, Shapes.cylinder(new Pos(0, 0, 0), 2, 1, STONE, Axis.Y, true).size());
	}

	@Test
	void cylinderAlongXLiesOnItsSide() {
		Set<Pos> column = positions(Shapes.cylinder(new Pos(0, 0, 0), 1, 3, STONE, Axis.X, false));
		assertEquals(27, column.size());
		assertTrue(column.stream().allMatch(p -> p.x() >= 0 && p.x() <= 2));
	}

	@Test
	void sphereOfRadiusTwo() {
		Set<Pos> ball = positions(Shapes.sphere(new Pos(0, 0, 0), 2, STONE, false, false));
		assertEquals(81, ball.size());
		assertTrue(ball.containsAll(List.of(new Pos(2, 0, 0), new Pos(0, 2, 0), new Pos(0, 0, 2))));
		assertFalse(ball.contains(new Pos(2, 2, 0)));
	}

	@Test
	void hollowSphereIsAShell() {
		assertEquals(62, Shapes.sphere(new Pos(0, 0, 0), 2, STONE, true, false).size());
	}

	@Test
	void domeKeepsTheUpperHalf() {
		Set<Pos> dome = positions(Shapes.sphere(new Pos(0, 0, 0), 2, STONE, false, true));
		assertEquals(51, dome.size());
		assertTrue(dome.stream().allMatch(p -> p.y() >= 0));
	}

	@Test
	void coneNarrowsToAPoint() {
		Set<Pos> cone = positions(Shapes.cone(new Pos(0, 0, 0), 2, 3, STONE, false));
		assertEquals(31, cone.size());
		assertEquals(Set.of(new Pos(0, 2, 0)), cone.stream().filter(p -> p.y() == 2).collect(java.util.stream.Collectors.toSet()));
	}

	@Test
	void roundArchSpansAndPeaks() {
		Set<Pos> arch = positions(Shapes.arch(new Pos(0, 0, 0), new Pos(4, 0, 0), 2, STONE, 1, ArchStyle.ROUND));
		assertTrue(arch.containsAll(List.of(new Pos(0, 0, 0), new Pos(4, 0, 0), new Pos(2, 2, 0))), arch.toString());
		assertTrue(mirroredAboutX(arch, 4), arch.toString());
		assertTrue(connected(arch), arch.toString());
	}

	@Test
	void pointedArchMeetsInAPoint() {
		Set<Pos> pointed = positions(Shapes.arch(new Pos(0, 0, 0), new Pos(6, 0, 0), 4, STONE, 1, ArchStyle.POINTED));
		Set<Pos> round = positions(Shapes.arch(new Pos(0, 0, 0), new Pos(6, 0, 0), 4, STONE, 1, ArchStyle.ROUND));
		assertTrue(pointed.contains(new Pos(3, 4, 0)), pointed.toString());
		assertFalse(pointed.contains(new Pos(2, 4, 0)), "a pointed arch has a point, not a flat top: " + pointed);
		assertTrue(round.contains(new Pos(2, 4, 0)), "a round arch has full shoulders: " + round);
		assertTrue(mirroredAboutX(pointed, 6), pointed.toString());
		assertTrue(connected(pointed), pointed.toString());
	}

	@Test
	void thickArchGrowsToTheRightOfItsSpan() {
		Set<Pos> arch = positions(Shapes.arch(new Pos(0, 0, 0), new Pos(4, 0, 0), 2, STONE, 2, ArchStyle.ROUND));
		assertTrue(arch.contains(new Pos(2, 2, 0)) && arch.contains(new Pos(2, 2, -1)), arch.toString());
	}

	@Test
	void slantedArchIsRefused() {
		assertThrows(IllegalArgumentException.class,
				() -> Shapes.arch(new Pos(0, 0, 0), new Pos(3, 0, 2), 2, STONE, 1, ArchStyle.ROUND));
	}
}
