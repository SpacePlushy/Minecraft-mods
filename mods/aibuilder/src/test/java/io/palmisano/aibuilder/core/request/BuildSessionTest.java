package io.palmisano.aibuilder.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.gadgets.GadgetUse;
import io.palmisano.aibuilder.core.shapes.Box;
import org.junit.jupiter.api.Test;

/** A player's session keeps the gadgets its builds placed, less those cleared away since (M9 review). */
class BuildSessionTest {
	@Test
	void aClearedGadgetLeavesTheSession() {
		GadgetUse adder = new GadgetUse("adder", "{\"bits\":4}", new Pos(-27, 1, 20), "forward", 1);
		GadgetUse number = new GadgetUse("number", "{\"bits\":5}", new Pos(-25, 1, 52), "forward", 1);
		BuildSession session = new BuildSession("minecraft:overworld", "a calculator", new Pos(0, 64, 0), Facing.SOUTH, SizeTier.EPIC,
				Box.of(0, 0, 0, 1, 1, 1), "id", List.of("a working calculator"), 1, false, "").withGadgets(List.of(adder, number));
		assertEquals(List.of(adder), session.withoutGadgets(List.of(number)).gadgets());
	}
}
