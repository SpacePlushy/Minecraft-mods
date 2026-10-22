package io.palmisano.aibuilder.core.shapes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import org.junit.jupiter.api.Test;

class RoofsTest {
	static final BlockSpec STAIRS = BlockSpec.of("minecraft:oak_stairs");
	static final BlockSpec SLAB = BlockSpec.of("minecraft:oak_slab");
	static final BlockSpec PLANKS = BlockSpec.of("minecraft:oak_planks");

	static Map<Pos, BlockSpec> byPos(List<LocalBlock> blocks) {
		return blocks.stream().collect(Collectors.toMap(LocalBlock::pos, LocalBlock::block));
	}

	static void assertStair(Map<Pos, BlockSpec> roof, Pos p, String facing) {
		BlockSpec b = roof.get(p);
		assertEquals("minecraft:oak_stairs", b == null ? null : b.id(), "no stair at " + p);
		assertEquals(Map.of("facing", facing, "half", "bottom"), b.state(), "at " + p);
	}

	static void assertSlab(Map<Pos, BlockSpec> roof, Pos p) {
		BlockSpec b = roof.get(p);
		assertEquals("minecraft:oak_slab", b == null ? null : b.id(), "no slab at " + p);
		assertEquals(Map.of("type", "bottom"), b.state());
	}

	@Test
	void gableRowsClimbToTheRidge() {
		Map<Pos, BlockSpec> roof = byPos(Roofs.roof(Box.of(0, 0, 0, 4, 0, 2), RoofStyle.GABLE, STAIRS, SLAB, Axis.Z, 0, null));
		assertEquals(15, roof.size());
		for (int z = 0; z <= 2; z++) {
			assertStair(roof, new Pos(0, 0, z), "right");
			assertStair(roof, new Pos(4, 0, z), "left");
			assertStair(roof, new Pos(1, 1, z), "right");
			assertStair(roof, new Pos(3, 1, z), "left");
			assertSlab(roof, new Pos(2, 2, z));
		}
	}

	@Test
	void evenWidthsMeetWithoutASlab() {
		Map<Pos, BlockSpec> roof = byPos(Roofs.roof(Box.of(0, 0, 0, 3, 0, 0), RoofStyle.GABLE, STAIRS, null, Axis.Z, 0, null));
		assertEquals(4, roof.size());
		assertStair(roof, new Pos(0, 0, 0), "right");
		assertStair(roof, new Pos(3, 0, 0), "left");
		assertStair(roof, new Pos(1, 1, 0), "right");
		assertStair(roof, new Pos(2, 1, 0), "left");
	}

	@Test
	void overhangWidensTheEaves() {
		Map<Pos, BlockSpec> roof = byPos(Roofs.roof(Box.of(0, 0, 0, 4, 0, 2), RoofStyle.GABLE, STAIRS, SLAB, Axis.Z, 1, null));
		for (int z = -1; z <= 3; z++) {
			assertStair(roof, new Pos(-1, 0, z), "right");
			assertStair(roof, new Pos(5, 0, z), "left");
			assertSlab(roof, new Pos(2, 3, z));
		}
		assertNull(roof.get(new Pos(-1, 0, 4)));
	}

	@Test
	void gableEndsFillBetweenTheSlopes() {
		Map<Pos, BlockSpec> roof = byPos(Roofs.roof(Box.of(0, 0, 0, 4, 0, 2), RoofStyle.GABLE, STAIRS, SLAB, Axis.Z, 0, PLANKS));
		for (int z : new int[] {0, 2}) {
			for (int x = 1; x <= 3; x++) {
				assertEquals(PLANKS, roof.get(new Pos(x, 0, z)), "gable at " + x + ",0," + z);
			}
			assertEquals(PLANKS, roof.get(new Pos(2, 1, z)));
		}
		assertNull(roof.get(new Pos(2, 0, 1)), "the gable fills only the end faces");
	}

	@Test
	void oddGableWithoutASlabIsRefused() {
		assertThrows(IllegalArgumentException.class,
				() -> Roofs.roof(Box.of(0, 0, 0, 4, 0, 2), RoofStyle.GABLE, STAIRS, null, Axis.Z, 0, null));
	}

	@Test
	void ridgeAlongXSlopesForwardAndBack() {
		Map<Pos, BlockSpec> roof = byPos(Roofs.roof(Box.of(0, 0, 0, 4, 0, 2), RoofStyle.GABLE, STAIRS, SLAB, null, 0, null));
		assertStair(roof, new Pos(0, 0, 0), "forward");
		assertStair(roof, new Pos(0, 0, 2), "back");
		assertSlab(roof, new Pos(0, 1, 1));
	}

	@Test
	void hipRoofSlopesOnAllSides() {
		Map<Pos, BlockSpec> roof = byPos(Roofs.roof(Box.of(0, 0, 0, 4, 0, 4), RoofStyle.HIP, STAIRS, SLAB, null, 0, null));
		assertEquals(25, roof.size());
		assertEquals(16, roof.keySet().stream().filter(p -> p.y() == 0).count());
		assertEquals(8, roof.keySet().stream().filter(p -> p.y() == 1).count());
		assertStair(roof, new Pos(2, 0, 0), "forward");
		assertStair(roof, new Pos(2, 0, 4), "back");
		assertStair(roof, new Pos(0, 0, 2), "right");
		assertStair(roof, new Pos(4, 0, 2), "left");
		assertStair(roof, new Pos(0, 0, 0), "forward");
		assertSlab(roof, new Pos(2, 2, 2));
	}

	@Test
	void flatRoofIsOneLayerOfSlabs() {
		List<LocalBlock> roof = Roofs.roof(Box.of(0, 3, 0, 2, 3, 2), RoofStyle.FLAT, STAIRS, SLAB, null, 0, null);
		assertEquals(9, roof.size());
		assertTrue(roof.stream().allMatch(b -> b.pos().y() == 3 && b.block().id().equals("minecraft:oak_slab")));
	}
}
