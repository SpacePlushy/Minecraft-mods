package io.palmisano.aibuilder.core.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;

import io.palmisano.aibuilder.core.shapes.Box;
import org.junit.jupiter.api.Test;

/** What Claude reads about the site before it builds (spec §11). */
class SurveyEncoderTest {
	private static final Column GRASS = new Column(0, 0, Column.NONE, "minecraft:grass_block", 0, 0, false, 0, "");

	/** A width × depth snapshot starting at local (minX, minZ), each column from {@code at(x, z)}. */
	private static TerrainSnapshot snapshot(int minX, int minZ, int width, int depth, BiFunction<Integer, Integer, Column> at) {
		List<Column> columns = new ArrayList<>();
		for (int z = minZ; z < minZ + depth; z++) {
			for (int x = minX; x < minX + width; x++) {
				columns.add(at.apply(x, z));
			}
		}
		return new TerrainSnapshot(minX, minZ, width, depth, columns);
	}

	private static Column floorAt(int y) {
		return new Column(y, y, Column.NONE, "minecraft:grass_block", 0, 0, false, 0, "");
	}

	@Test
	void flatGrassReadsAsOneRun() {
		String text = SurveyEncoder.encode(snapshot(-2, 0, 5, 3, (x, z) -> GRASS), Box.of(-2, -1, 0, 2, 4, 2), 30_000);
		assertEquals("""
				Survey: 5×3 columns, x -2..2, z 0..2 (local frame: +x right, +z forward), 1×1 cells. Heights are y relative to the ground at the origin (y=0).
				Allowed box: x -2..2, y -1..4, z 0..2.
				Floor: 0..0, average 0.0; slope 0.0 (average height step between neighbouring columns).
				Water: none. Lava: none. Ceilings: none.
				Trees: none.
				Protected (looks player-made; writes there are refused): none.
				Surface blocks: a=grass_block
				Surface (rows by z, forward from z 0; cells by x, rightward from x -2; a run like a5 is five cells):
				z 0: a5
				z 1: a5
				z 2: a5
				Floor heights (same layout; 3x5 is height 3 five times):
				z 0: 0x5
				z 1: 0x5
				z 2: 0x5
				""", text);
	}

	/** A drop with nothing within reach below: marked, and left out of the floor's numbers. */
	/** "=" joins a key to its block in the palette, so it's never a key. */
	@Test
	void equalsIsNeverAKey() {
		String text = SurveyEncoder.encode(snapshot(0, 0, 60, 1, (x, z) -> new Column(0, 0, Column.NONE, "minecraft:block_" + x, 0, 0, false, 0, "")),
				Box.of(0, -1, 0, 59, 4, 0), 30_000);
		String row = text.lines().dropWhile(line -> !line.startsWith("Surface (")).skip(1).findFirst().orElseThrow();
		assertFalse(row.substring(5).contains("="), row);
	}

	@Test
	void noFloorColumnsAreMarkedAndLeftOutOfTheStats() {
		Column drop = new Column(Column.NONE, Column.NONE, Column.NONE, "minecraft:air", 0, 0, false, 0, "");
		String text = SurveyEncoder.encode(snapshot(0, 0, 3, 1, (x, z) -> x == 1 ? drop : floorAt(2 * x)), Box.of(0, 0, 0, 2, 9, 0), 30_000);
		assertTrue(text.contains("Floor: 0..4, average 2.0; slope 0.0 "), text);
		assertTrue(text.contains("No floor within 64 blocks below: 1 column (a drop or the void)."), text);
		assertTrue(text.contains("Surface blocks: a=grass_block _=no floor\n"), text);
		assertTrue(text.contains("cells by x, rightward from x 0; a run like a5 is five cells):\nz 0: a _ a\n"), text);
		assertTrue(text.contains("Floor heights (same layout; 3x5 is height 3 five times):\nz 0: 0 _ 4\n"), text);
	}

	@Test
	void aSurveyWithNoFloorAnywhereSaysSo() {
		Column drop = new Column(Column.NONE, Column.NONE, Column.NONE, "minecraft:air", 0, 0, false, 0, "");
		String text = SurveyEncoder.encode(snapshot(0, 0, 2, 2, (x, z) -> drop), Box.of(0, 0, 0, 1, 9, 1), 30_000);
		assertTrue(text.contains("Floor: none within 64 blocks below anywhere here (a drop or the void).\n"), text);
		assertTrue(text.contains("z 0: _x2\n"), text);
	}

	@Test
	void aHillShowsInHeightsAndSlope() {
		String text = SurveyEncoder.encode(snapshot(0, 0, 3, 1, (x, z) -> floorAt(2 * x)), Box.of(0, 0, 0, 2, 9, 0), 30_000);
		assertTrue(text.contains("Floor: 0..4, average 2.0; slope 2.0 "), text);
		assertTrue(text.contains("Floor heights (same layout; 3x5 is height 3 five times):\nz 0: 0 2 4\n"), text);
	}

	@Test
	void waterCoverageAndDepth() {
		Column pond = new Column(0, 0, Column.NONE, "minecraft:water", 3, 0, false, 0, "");
		String text = SurveyEncoder.encode(snapshot(0, 0, 5, 3, (x, z) -> x >= 3 && z <= 1 ? pond : GRASS), Box.of(0, -2, 0, 4, 5, 2), 30_000);
		assertTrue(text.contains("Water: 4 of 15 columns (27%), deepest 3. Lava: none. Ceilings: none."), text);
		assertTrue(text.contains("Surface blocks: a=grass_block b=water\n"), text);
		assertTrue(text.contains("z 0: a3 b2\nz 1: a3 b2\nz 2: a5\n"), text);
	}

	@Test
	void clustersBecomeBoxes() {
		Column tree = new Column(0, 6, Column.NONE, "minecraft:grass_block", 0, 0, true, 0, "");
		Column house = new Column(0, 4, Column.NONE, "minecraft:grass_block", 0, 0, false, 5, "minecraft:oak_planks");
		String text = SurveyEncoder.encode(snapshot(0, 0, 6, 3, (x, z) -> {
			if (x <= 1 && z <= 1) {
				return tree;
			}
			if (x == 5 && z == 2) {
				return tree;
			}
			return x >= 2 && x <= 3 && z <= 1 ? house : GRASS;
		}), Box.of(0, 0, 0, 5, 9, 2), 30_000);
		assertTrue(text.contains("Trees: 2 clusters: x 0..1, z 0..1 (4 columns); x 5..5, z 2..2 (1 column)."), text);
		assertTrue(text.contains("Protected (looks player-made; writes there are refused): x 2..3, z 0..1: 20 blocks, mostly oak_planks."), text);
	}

	/** Deep water and a column with no floor in range (the ground finder's fallback) still read as numbers. */
	@Test
	void oceanAndVoidColumnsReadSensibly() {
		Column ocean = new Column(0, 0, Column.NONE, "minecraft:water", 40, 0, false, 0, "");
		Column voidColumn = new Column(-1, Integer.MIN_VALUE, Column.NONE, "minecraft:air", 0, 0, false, 0, "");
		String text = SurveyEncoder.encode(snapshot(0, 0, 4, 1, (x, z) -> x < 2 ? ocean : voidColumn), Box.of(0, -20, 0, 3, 20, 0), 30_000);
		assertTrue(text.contains("Water: 2 of 4 columns (50%), deepest 40."), text);
		assertTrue(text.contains("z 0: 0x2 -1x2\n"), text);
	}

	/** Rough terrain would blow the cap at full size: the maps coarsen, and no row is cut short. */
	@Test
	void roughTerrainCoarsensToFit() {
		Random random = new Random(42);
		String[] blocks = {"minecraft:stone", "minecraft:dirt", "minecraft:grass_block", "minecraft:sand", "minecraft:gravel"};
		TerrainSnapshot rough = snapshot(-48, -8, 97, 97, (x, z) -> {
			int y = random.nextInt(60) - 20;
			return new Column(y, y, Column.NONE, blocks[random.nextInt(blocks.length)], 0, 0, false, 0, "");
		});
		String text = SurveyEncoder.encode(rough, Box.of(-40, -20, 0, 39, 79, 79), 30_000);
		assertTrue(text.length() <= 30_000, text.length() + " characters");
		assertTrue(!text.contains(", 1×1 cells."), "should have coarsened: " + text.lines().findFirst().orElse(""));
		long surfaceRows = text.lines().dropWhile(line -> !line.startsWith("Surface (")).skip(1).takeWhile(line -> line.startsWith("z ")).count();
		long heightRows = text.lines().dropWhile(line -> !line.startsWith("Floor heights (")).skip(1).takeWhile(line -> line.startsWith("z ")).count();
		assertEquals(surfaceRows, heightRows);
		assertTrue(surfaceRows > 1 && text.endsWith("\n"), "the maps must be whole");
	}
}
