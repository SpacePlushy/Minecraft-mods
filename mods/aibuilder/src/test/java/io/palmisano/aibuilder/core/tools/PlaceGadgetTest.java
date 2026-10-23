package io.palmisano.aibuilder.core.tools;

import static io.palmisano.aibuilder.core.tools.ToolExecutorTest.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.palmisano.aibuilder.core.blocks.FakeCatalog;
import io.palmisano.aibuilder.core.blocks.Validator;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.ProtectionMask;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import org.junit.jupiter.api.Test;

/** place_gadget (spec v2 §5.3): a gadget goes through the queue as any write does, whole or not at all. */
class PlaceGadgetTest {
	private static final Box ALLOWED = Box.of(-8, -2, 0, 7, 15, 40);

	private final RecordingSink sink = new RecordingSink();
	private final FakeWorld world = new FakeWorld();

	private ToolExecutor briefed() {
		ToolExecutor tools = new ToolExecutor(new Validator(FakeCatalog.standard(), Validator.DEFAULT_BANNED), new LocalFrame(new Pos(0, 64, 0),
				Facing.SOUTH), ALLOWED, SizeTier.SMALL, 100_000, sink, new Budget(150), world);
		tools.call("submit_brief", json(ToolExecutorTest.BRIEF));
		return tools;
	}

	@Test
	void theAnswerListsPortsAndSettleTicks() {
		ToolResult result = briefed().call("place_gadget", json("{\"kind\":\"adder\",\"at\":[-6,0,2],\"options\":{\"bits\":1}}"));
		assertFalse(result.isError(), result.text());
		assertTrue(result.text().contains("an adder (1 bits)") || result.text().contains("an adder (1 bit)"), result.text());
		assertTrue(result.text().contains("a0 (") && result.text().contains("b0 (") && result.text().contains("s1 ("), result.text());
		assertTrue(result.text().contains("settles within"), result.text());
		assertEquals(1, sink.gadgets.size());
		assertTrue(sink.queuedCount() > 0, "nothing was queued");
	}

	@Test
	void aGadgetPastTheBoxIsRefusedWhole() {
		ToolResult result = briefed().call("place_gadget", json("{\"kind\":\"adder\",\"at\":[0,0,2],\"options\":{\"bits\":2}}"));
		assertTrue(result.isError(), result.text());
		assertTrue(result.text().startsWith("out_of_bounds:") && result.text().contains("nothing was placed"), result.text());
		assertEquals(0, sink.queuedCount());
		assertTrue(sink.gadgets.isEmpty());
	}

	@Test
	void aGadgetOverSomethingPlayerMadeIsRefusedWhole() {
		ProtectionMask mask = new ProtectionMask(ALLOWED);
		mask.protect(new Pos(1, 1, 3));
		world.protection = mask;
		ToolResult result = briefed().call("place_gadget", json("{\"kind\":\"lamp_row\",\"at\":[0,0,2],\"options\":{\"bits\":2}}"));
		assertTrue(result.isError(), result.text());
		assertTrue(result.text().startsWith("protected:") && result.text().contains("(1, 1, 3)"), result.text());
		assertEquals(0, sink.queuedCount());
	}

	@Test
	void gadgetsNeedABlockBetweenThem() {
		ToolExecutor tools = briefed();
		assertFalse(tools.call("place_gadget", json("{\"kind\":\"lamp_row\",\"at\":[0,0,2],\"options\":{\"bits\":1}}")).isError());
		ToolResult touching = tools.call("place_gadget", json("{\"kind\":\"lamp_row\",\"at\":[1,0,2],\"options\":{\"bits\":1}}"));
		assertTrue(touching.isError() && touching.text().startsWith("too_close:"), touching.text());
		assertFalse(tools.call("place_gadget", json("{\"kind\":\"lamp_row\",\"at\":[2,0,2],\"options\":{\"bits\":1}}")).isError());
		assertEquals(2, sink.gadgets.size());
	}

	@Test
	void aBadFacingOrKindIsABadArgument() {
		ToolExecutor tools = briefed();
		ToolResult facing = tools.call("place_gadget", json("{\"kind\":\"lamp_row\",\"at\":[0,0,2],\"facing\":\"north\"}"));
		assertTrue(facing.isError() && facing.text().startsWith("bad_argument:") && facing.text().contains("forward, right, back or left"), facing.text());
		ToolResult kind = tools.call("place_gadget", json("{\"kind\":\"teleporter\",\"at\":[0,0,2]}"));
		assertTrue(kind.isError() && kind.text().contains("there's no gadget called"), kind.text());
	}
}
