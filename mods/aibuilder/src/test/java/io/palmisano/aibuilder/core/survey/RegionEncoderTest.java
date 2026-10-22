package io.palmisano.aibuilder.core.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.IntStream;

import io.palmisano.aibuilder.core.shapes.Box;
import org.junit.jupiter.api.Test;

/** inspect_region's text: exact states, layer by layer (spec §8). */
class RegionEncoderTest {
	@Test
	void regionShowsLayersWithAPalette() {
		String stairs = "minecraft:oak_stairs[facing=forward,half=bottom]";
		// x fastest, then z, then y: a 3 × 2 × 2 region.
		List<String> states = List.of(
				"minecraft:stone", "minecraft:stone", stairs,
				"minecraft:stone", "minecraft:air", "minecraft:cave_air",
				"minecraft:air", "minecraft:air", "minecraft:air",
				"minecraft:air", "minecraft:air", "minecraft:air");
		assertEquals("""
				Region x 0..2, y 1..2, z 0..1 (local): 12 blocks, 2 kinds besides air. One block of rows per y, bottom up; rows by z, forward; cells by x, rightward; "." is air.
				a=stone
				b=oak_stairs[facing=forward,half=bottom]
				y 1:
				z 0: aab
				z 1: a..
				y 2:
				z 0: ...
				z 1: ...
				""", RegionEncoder.encode(Box.of(0, 1, 0, 2, 2, 1), states));
	}

	/** More kinds than one-character keys: every cell takes two characters, so rows stay aligned. */
	@Test
	void manyKindsUseTwoCharacterKeys() {
		List<String> states = IntStream.range(0, 70).mapToObj(i -> "minecraft:block_" + i).toList();
		String text = RegionEncoder.encode(Box.of(0, 0, 0, 69, 0, 0), states);
		String row = text.lines().filter(line -> line.startsWith("z 0: ")).findFirst().orElseThrow();
		assertEquals("z 0: ".length() + 140, row.length());
	}
}
