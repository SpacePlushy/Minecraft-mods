package io.palmisano.aibuilder.core.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.frame.Pos;
import org.junit.jupiter.api.Test;

/** The other blocks a two-block block writes (a door's top half, a bed's head), in the local frame (PR review). */
class FootprintTest {
	private static final Pos AT = new Pos(2, 0, 5);
	private final FakeCatalog catalog = FakeCatalog.standard();

	private ResolvedBlock block(String id, Map<String, String> state) {
		return new ResolvedBlock(catalog.find(id).orElseThrow(), state);
	}

	@Test
	void aDoorsTopHalfIsAbove() {
		assertEquals(List.of(new Pos(2, 1, 5)), Footprint.others(block("minecraft:oak_door", Map.of("facing", "south")), AT));
		assertEquals(List.of(new Pos(2, 1, 5)), Footprint.others(block("minecraft:oak_door", Map.of("half", "lower")), AT));
	}

	@Test
	void aTopHalfOnItsOwnIsOneBlock() {
		assertEquals(List.of(), Footprint.others(block("minecraft:oak_door", Map.of("half", "upper")), AT));
		assertEquals(List.of(), Footprint.others(block("minecraft:red_bed", Map.of("part", "head")), AT));
	}

	/** Before the frame's rotation, south is forward (+z) and west is right (+x); vanilla's bed faces north unless told. */
	@Test
	void aBedsHeadIsWhereItFaces() {
		assertEquals(List.of(new Pos(2, 0, 6)), Footprint.others(block("minecraft:red_bed", Map.of("facing", "south")), AT));
		assertEquals(List.of(new Pos(2, 0, 4)), Footprint.others(block("minecraft:red_bed", Map.of("facing", "north")), AT));
		assertEquals(List.of(new Pos(3, 0, 5)), Footprint.others(block("minecraft:red_bed", Map.of("facing", "west")), AT));
		assertEquals(List.of(new Pos(1, 0, 5)), Footprint.others(block("minecraft:red_bed", Map.of("facing", "east")), AT));
		assertEquals(List.of(new Pos(2, 0, 4)), Footprint.others(block("minecraft:red_bed", Map.of()), AT));
	}

	@Test
	void anyOtherBlockIsJustItself() {
		assertEquals(List.of(), Footprint.others(block("minecraft:stone", Map.of()), AT));
		assertEquals(List.of(), Footprint.others(block("minecraft:oak_stairs", Map.of("half", "top")), AT));
	}
}
