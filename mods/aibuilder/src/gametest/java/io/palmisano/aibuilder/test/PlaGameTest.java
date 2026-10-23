package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.GadgetRig.GADGETS;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.palmisano.aibuilder.core.gadgets.Gadget;
import io.palmisano.aibuilder.core.gadgets.Pla;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** The logic array in a real world (M9 plan, Task 1): the redstone it's built from works, in every facing. */
public class PlaGameTest {
	/** a XOR b, in every facing: all four input combinations read right after the stated settle ticks. */
	@GameTest(structure = GADGETS, maxTicks = 1200)
	public void aPlaXorReadsRightInEveryFacing(GameTestHelper helper) {
		Gadget xor = Pla.build(List.of(Set.of(1, 2)), Set.of(), new Pla.Layout("xor", List.of("a", "b"), List.of("x"), List.of(2), Set.of()));
		GadgetRig.truthTable(helper, xor, v -> v == 1 || v == 2 ? Set.of("x") : Set.of());
	}

	/** A full adder cell, in every facing: the sum out the back and the carry out the front, for all eight combinations. */
	@GameTest(structure = GADGETS, maxTicks = 2400)
	public void aPlaFullAdderReadsRightInEveryFacing(GameTestHelper helper) {
		Set<Integer> sum = new HashSet<>();
		Set<Integer> carry = new HashSet<>();
		for (int v = 0; v < 8; v++) {
			if (Integer.bitCount(v) % 2 == 1) {
				sum.add(v);
			}
			if (Integer.bitCount(v) >= 2) {
				carry.add(v);
			}
		}
		Gadget cell = Pla.build(List.of(sum, carry), Set.of(), new Pla.Layout("adder_cell", List.of("c", "a", "b"), List.of("s", "cout"),
				List.of(2, 10), Set.of(1)));
		GadgetRig.truthTable(helper, cell, v -> {
			Set<String> lit = new HashSet<>();
			if (Integer.bitCount(v) % 2 == 1) {
				lit.add("s");
			}
			if (Integer.bitCount(v) >= 2) {
				lit.add("cout");
			}
			return lit;
		});
	}
}
