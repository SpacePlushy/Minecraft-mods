package io.palmisano.aibuilder.core.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** search_blocks: Claude finds the exact ids and properties of any block, other mods' included (spec §8). */
class BlockSearchTest {
	private final FakeCatalog catalog = FakeCatalog.standard();

	@Test
	void searchFindsEveryWord() {
		assertEquals("minecraft:oak_stairs", BlockSearch.search(catalog, "oak stair", 10).getFirst().id());
	}

	@Test
	void shorterIdsComeFirst() {
		assertEquals(List.of("minecraft:oak_log", "minecraft:oak_door", "minecraft:oak_sign", "minecraft:oak_slab", "minecraft:oak_planks",
				"minecraft:oak_stairs"),
				BlockSearch.search(catalog, "minecraft:oak", 10).stream().map(BlockInfo::id).toList());
	}

	@Test
	void searchFallsBackToSuggestions() {
		List<BlockInfo> found = BlockSearch.search(catalog, "oke plank", 5);
		assertTrue(found.stream().anyMatch(b -> b.id().equals("minecraft:oak_planks")), found.toString());
	}

	@Test
	void describeUsesRelativeDirections() {
		assertEquals("oak_stairs: facing back|forward|right|left; half top|bottom; shape straight|inner_left|inner_right|outer_left|outer_right;"
				+ " waterlogged true|false", BlockSearch.describe(catalog.find("minecraft:oak_stairs").orElseThrow()));
		assertEquals("vine: back true|false; left true|false; forward true|false; right true|false; up true|false",
				BlockSearch.describe(catalog.find("minecraft:vine").orElseThrow()));
		assertEquals("stone", BlockSearch.describe(catalog.find("minecraft:stone").orElseThrow()));
	}
}
