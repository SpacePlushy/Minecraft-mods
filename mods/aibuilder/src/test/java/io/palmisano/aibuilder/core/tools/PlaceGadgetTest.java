package io.palmisano.aibuilder.core.tools;

import static io.palmisano.aibuilder.core.tools.ToolExecutorTest.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

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

	private ToolExecutor leverAndLamp() {
		ToolExecutor tools = briefed();
		assertFalse(tools.call("place_gadget", json("{\"kind\":\"lever_row\",\"at\":[0,0,2],\"options\":{\"bits\":1}}")).isError());
		assertFalse(tools.call("place_gadget", json("{\"kind\":\"lamp_row\",\"at\":[4,0,14],\"options\":{\"bits\":1}}")).isError());
		return tools;
	}

	/** wire joins an output port to an input port through the queue, as any write: dust, and stone only over air. */
	@Test
	void aWireJoinsPortsThroughTheQueue() {
		ToolExecutor tools = leverAndLamp();
		int before = sink.queuedCount();
		world.cells.put(new Pos(2, 0, 7), io.palmisano.aibuilder.core.wire.Cell.FREE);
		ToolResult wired = tools.call("wire", json("{\"from\":[[0,1,5]],\"to\":[[4,1,13]]}"));
		assertFalse(wired.isError(), wired.text());
		assertTrue(wired.text().startsWith("Wired in0 (0, 1, 5) to in0 (4, 1, 13): 13 dust, 1 stone under it."), wired.text());
		assertEquals(before + 14, sink.queuedCount());
	}

	/** A wire runs from an output port the build placed to an input port it placed: anything else is refused, saying what's there. */
	@Test
	void aWireRunsFromAnOutputToAnInput() {
		ToolExecutor tools = leverAndLamp();
		int before = sink.queuedCount();
		ToolResult backwards = tools.call("wire", json("{\"from\":[[4,1,13]],\"to\":[[0,1,5]]}"));
		assertTrue(backwards.isError() && backwards.text().startsWith("bad_argument: from (4, 1, 13) is the input in0 of a row of 1 lamp: a wire"
				+ " runs from an output port to an input port"), backwards.text());
		ToolResult nowhere = tools.call("wire", json("{\"from\":[[0,1,5]],\"to\":[[9,1,9]]}"));
		assertTrue(nowhere.isError() && nowhere.text().startsWith("bad_argument: to (9, 1, 9) isn't a port of a gadget this build placed"),
				nowhere.text());
		assertEquals(before, sink.queuedCount());
	}

	/** A show step names each digit and number of the build, the earlier builds' too, with the spots to read them by. */
	@Test
	void aShowStepNamesTheBuildsDisplays() {
		ToolExecutor tools = briefed();
		tools.refiningGadgets(List.of(new io.palmisano.aibuilder.core.gadgets.GadgetUse("number", "{\"bits\":2}", new Pos(-6, 0, 2), "forward", 1),
				new io.palmisano.aibuilder.core.gadgets.GadgetUse("lamp_row", "{\"bits\":1}", new Pos(-6, 0, 30), "forward", 1)), 2);
		ToolResult result = tools.call("test_redstone", json("{\"steps\":[{\"show\":true}]}"));
		assertFalse(result.isError(), result.text());
		RedstoneSteps.Show show = (RedstoneSteps.Show) world.tested.getLast().getFirst();
		assertEquals(1, show.displays().size(), "a lamp row isn't a display");
		assertEquals("number", show.displays().getFirst().what());
		assertEquals(new Pos(-6, 0, 2), show.displays().getFirst().at());
		assertTrue(show.displays().getFirst().marks().containsKey("ones segment a"));
	}

	/** A row given a level runs at it: its dust goes no higher. */
	@Test
	void aRowOfWiresRunsAtItsLevel() {
		ToolExecutor tools = leverAndLamp();
		int before = sink.batches.size();
		ToolResult wired = tools.call("wire", json("{\"from\":[[0,1,5]],\"to\":[[4,1,13]],\"level\":4}"));
		assertFalse(wired.isError(), wired.text());
		int top = sink.batches.subList(before, sink.batches.size()).stream().flatMap(List::stream).filter(p -> p.block().info().id().contains("redstone_wire"))
				.mapToInt(p -> p.pos().y()).max().orElseThrow();
		assertEquals(64 + 4, top);
		ToolResult via = tools.call("wire", json("{\"from\":[[0,1,5]],\"to\":[[4,1,13]],\"via\":[[0,3,9]],\"level\":4}"));
		assertTrue(via.isError() && via.text().contains("level is for a row of wires"), via.text());
	}

	/** Touching other redstone, a wire is refused whole: nothing of it is queued. */
	@Test
	void aWireTouchingRedstoneIsRefusedWhole() {
		ToolExecutor tools = leverAndLamp();
		int before = sink.queuedCount();
		world.cells.put(new Pos(3, 1, 8), io.palmisano.aibuilder.core.wire.Cell.COMPONENT);
		ToolResult refused = tools.call("wire", json("{\"from\":[[0,1,5]],\"to\":[[4,1,13]]}"));
		assertTrue(refused.isError() && refused.text().startsWith("touches_redstone: (3, 1, 8) is redstone, beside the wire at (3, 1, 7)")
				&& refused.text().contains("nothing was placed"), refused.text());
		assertEquals(before, sink.queuedCount());
	}

	/** A gadget would erase the build's redstone in its box and join any beside it: refused whole, saying where (M9 review). */
	@Test
	void aGadgetOverOrBesideRedstoneIsRefusedWhole() {
		ToolExecutor tools = briefed();
		String row = "{\"kind\":\"lamp_row\",\"at\":[0,0,2],\"options\":{\"bits\":1}}";
		world.cells.put(new Pos(0, 1, 3), io.palmisano.aibuilder.core.wire.Cell.DUST);
		ToolResult over = tools.call("place_gadget", json(row));
		assertTrue(over.isError() && over.text().startsWith("touches_redstone: (0, 1, 3) is dust, inside where a row of 1 lamp would go")
				&& over.text().contains("nothing was placed"), over.text());
		world.cells.clear();
		world.cells.put(new Pos(1, 1, 2), io.palmisano.aibuilder.core.wire.Cell.COMPONENT);
		ToolResult beside = tools.call("place_gadget", json(row));
		assertTrue(beside.isError() && beside.text().startsWith("touches_redstone: (1, 1, 2) is redstone, beside where a row of 1 lamp would go"),
				beside.text());
		assertEquals(0, sink.queuedCount());
		assertTrue(sink.gadgets.isEmpty());
		world.cells.clear();
		assertFalse(tools.call("place_gadget", json(row)).isError());
	}

	/** Clearing a gadget's whole box takes it out of the session, so one may go there again; a part cleared doesn't (M9 review). */
	@Test
	void clearingAGadgetsBoxLetsItBePlacedAgain() {
		ToolExecutor tools = briefed();
		assertFalse(tools.call("place_gadget", json("{\"kind\":\"lamp_row\",\"at\":[0,0,2],\"options\":{\"bits\":1}}")).isError());
		String next = "{\"kind\":\"lamp_row\",\"at\":[1,0,2],\"options\":{\"bits\":1}}";
		assertTrue(tools.call("place_gadget", json(next)).text().startsWith("too_close:"));
		assertFalse(tools.call("clear", json("{\"box\":[0,1,3,0,1,3]}")).isError());
		assertTrue(sink.gone.isEmpty(), "a lamp taken out leaves the gadget");
		assertTrue(tools.call("place_gadget", json(next)).text().startsWith("too_close:"));
		ToolResult cleared = tools.call("clear", json("{\"box\":[-1,0,1,0,1,3]}"));
		assertFalse(cleared.isError(), cleared.text());
		assertEquals(1, sink.gone.size());
		assertTrue(cleared.text().contains("a row of 1 lamp at (0, 0, 2) is gone"), cleared.text());
		assertFalse(tools.call("place_gadget", json(next)).isError());
	}
}
