package io.palmisano.aibuilder.core.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidatorTest {
	private final Validator validator = new Validator(FakeCatalog.standard(), Validator.DEFAULT_BANNED);

	private static BlockSpec spec(String id, String... state) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < state.length; i += 2) {
			map.put(state[i], state[i + 1]);
		}
		return new BlockSpec(id, map);
	}

	private ResolvedBlock valid(BlockSpec spec) {
		return assertInstanceOf(Validation.Valid.class, validator.validate(spec)).block();
	}

	private Rejection invalid(BlockSpec spec) {
		return assertInstanceOf(Validation.Invalid.class, validator.validate(spec)).rejection();
	}

	@Test
	void stairsFacingForwardBecomeSouth() {
		assertEquals(Map.of("facing", "south", "half", "bottom"), valid(spec("oak_stairs", "facing", "forward", "half", "bottom")).state());
	}

	@Test
	void handednessIsNotADirection() {
		assertEquals(Map.of("facing", "west", "hinge", "left"), valid(spec("oak_door", "facing", "right", "hinge", "left")).state());
	}

	@Test
	void stairCornersKeepTheirShape() {
		assertEquals(Map.of("facing", "east", "shape", "inner_left"), valid(spec("oak_stairs", "facing", "left", "shape", "inner_left")).state());
	}

	@Test
	void railShapesTranslateTokenByToken() {
		assertEquals(Map.of("shape", "north_south"), valid(spec("rail", "shape", "back_forward")).state());
	}

	@Test
	void vineSidesTranslateByName() {
		assertEquals(Map.of("west", "true"), valid(spec("vine", "right", "true")).state());
	}

	@Test
	void namespaceIsOptional() {
		assertEquals("minecraft:oak_log", valid(spec("OAK_LOG")).info().id());
	}

	@Test
	void unknownBlockSuggestsNames() {
		Rejection r = invalid(spec("oak_stair"));
		assertEquals(RejectReason.UNKNOWN_BLOCK, r.reason());
		assertTrue(r.detail().contains("minecraft:oak_stairs"), r.detail());
	}

	@Test
	void compassNamesAreRejectedWithAHint() {
		Rejection r = invalid(spec("oak_stairs", "facing", "east"));
		assertEquals(RejectReason.COMPASS_DIRECTION, r.reason());
		assertTrue(r.detail().contains("forward"), r.detail());
	}

	@Test
	void unknownPropertyListsTheRealOnes() {
		Rejection r = invalid(spec("oak_stairs", "color", "red"));
		assertEquals(RejectReason.BAD_PROPERTY, r.reason());
		assertTrue(r.detail().contains("facing") && r.detail().contains("half"), r.detail());
	}

	@Test
	void badValueListsAllowedValuesInRelativeWords() {
		Rejection r = invalid(spec("oak_stairs", "facing", "up"));
		assertEquals(RejectReason.BAD_VALUE, r.reason());
		assertTrue(r.detail().contains("forward"), r.detail());
		assertFalse(r.detail().contains("south"), r.detail());
	}

	@Test
	void unbreakableAndListedBlocksAreBanned() {
		assertEquals(RejectReason.BANNED_BLOCK, invalid(spec("bedrock")).reason());
		assertEquals(RejectReason.BANNED_BLOCK, invalid(spec("tnt")).reason());
	}
}
