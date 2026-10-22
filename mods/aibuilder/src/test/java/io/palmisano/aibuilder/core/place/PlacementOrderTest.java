package io.palmisano.aibuilder.core.place;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.blocks.BlockInfo;
import io.palmisano.aibuilder.core.blocks.PlacementPass;
import io.palmisano.aibuilder.core.blocks.ResolvedBlock;
import io.palmisano.aibuilder.core.frame.Pos;
import org.junit.jupiter.api.Test;

class PlacementOrderTest {
	private static Placement at(String id, PlacementPass pass, int y) {
		return new Placement(new Pos(0, y, 0), new ResolvedBlock(new BlockInfo(id, Map.of(), false, pass, false), Map.of()), null);
	}

	private static List<String> ids(List<Placement> placements) {
		return placements.stream().map(p -> p.block().info().id()).toList();
	}

	@Test
	void passesComeInOrderBottomUpWithinEach() {
		List<Placement> in = List.of(at("torch", PlacementPass.ATTACHED, 1), at("wall-high", PlacementPass.STRUCTURE, 2),
				at("wall-low", PlacementPass.STRUCTURE, 1), at("water", PlacementPass.FLUID, 0), at("sand", PlacementPass.FALLING, 3),
				at("stairs", PlacementPass.PARTIAL, 1));
		assertEquals(List.of("wall-low", "wall-high", "stairs", "sand", "water", "torch"), ids(PlacementOrder.sort(in)));
	}

	@Test
	void equalKeysKeepClaudesOrder() {
		List<Placement> in = List.of(at("first", PlacementPass.STRUCTURE, 4), at("second", PlacementPass.STRUCTURE, 4),
				at("third", PlacementPass.STRUCTURE, 4));
		assertEquals(List.of("first", "second", "third"), ids(PlacementOrder.sort(in)));
	}
}
