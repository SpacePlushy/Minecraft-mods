package io.palmisano.aibuilder.core.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collection;
import org.junit.jupiter.api.Test;

class BlockNamesTest {
	private final Collection<String> ids = FakeCatalog.standard().ids();

	@Test
	void closestNameComesFirst() {
		assertEquals("minecraft:oak_stairs", BlockNames.suggest(ids, "oak_stair", 5).getFirst());
		assertEquals("minecraft:stone", BlockNames.suggest(ids, "stone", 5).getFirst());
	}
}
