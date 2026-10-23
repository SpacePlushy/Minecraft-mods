package io.palmisano.aibuilder.core.tools;

import static io.palmisano.aibuilder.core.tools.ToolExecutorTest.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.palmisano.aibuilder.core.anim.Motion;
import io.palmisano.aibuilder.core.anim.MovingPart;
import io.palmisano.aibuilder.core.anim.Vec3;
import io.palmisano.aibuilder.core.blocks.FakeCatalog;
import io.palmisano.aibuilder.core.blocks.Validator;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.FireMask;
import io.palmisano.aibuilder.core.place.Placement;
import io.palmisano.aibuilder.core.place.ProtectionMask;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import org.junit.jupiter.api.Test;

/** Moving parts in the tools (spec v2 §4.1, §4.2): a write with a part makes displays; animate gives a part its motion. */
class MovingPartsTest {
	private final RecordingSink sink = new RecordingSink();
	private final FakeWorld world = new FakeWorld();

	private ToolExecutor tools() {
		ToolExecutor tools = new ToolExecutor(new Validator(FakeCatalog.standard(), Validator.DEFAULT_BANNED),
				new LocalFrame(new Pos(0, 64, 0), Facing.SOUTH), ToolExecutorTest.ALLOWED, SizeTier.SMALL, 100_000, sink, new Budget(150), world);
		tools.call("submit_brief", json(ToolExecutorTest.BRIEF));
		return tools;
	}

	private static final String WING = "{\"box\":[0,1,2,2,1,3],\"block\":\"oak_planks\",\"part\":\"wing\"}";

	@Test
	void aPartIsDisplaysNotBlocks() {
		ToolExecutor tools = tools();
		ToolResult result = tools.call("fill", json(WING));
		assertFalse(result.isError(), result.text());
		List<Placement> batch = sink.batches.getFirst();
		assertEquals(6, batch.size());
		assertTrue(batch.stream().allMatch(p -> p.part().equals("wing")), "every display is in the part");
		assertTrue(result.text().contains("wing"), result.text());
		assertEquals(6, tools.parts().get("wing").blocks().size());
		// Blocks are no part.
		tools.call("fill", json("{\"box\":[0,0,2,0,0,2],\"block\":\"stone\"}"));
		assertEquals("", sink.batches.get(1).getFirst().part());
	}

	@Test
	void aPartNameIsAShortWord() {
		ToolResult result = tools().call("fill", json("{\"box\":[0,1,2,0,1,2],\"block\":\"stone\",\"part\":\"Left Wing!\"}"));
		assertTrue(result.isError() && result.text().contains("part"), result.text());
	}

	@Test
	void aPartKeepsOffProtectedSpots() {
		world.protection = new ProtectionMask(ToolExecutorTest.ALLOWED);
		world.protection.protect(new Pos(1, 1, 2));
		ToolResult result = tools().call("fill", json(WING));
		assertEquals(5, sink.batches.getFirst().size());
		assertTrue(result.text().contains("protected"), result.text());
	}

	/** A display can't burn or light anything: lava and wood may be moving parts side by side. */
	@Test
	void aPartSkipsTheLavaRule() {
		world.fire = new FireMask(ToolExecutorTest.ALLOWED);
		world.fire.lava(new Pos(1, 1, 4));
		ToolExecutor tools = tools();
		assertFalse(tools.call("fill", json(WING)).text().contains("lava_near_flammable"));
		ToolResult lava = tools.call("set_blocks", json("{\"blocks\":[{\"pos\":[3,1,2],\"block\":\"lava\"}],\"part\":\"glow\"}"));
		assertFalse(lava.isError(), lava.text());
	}

	@Test
	void aBedCantBeAPart() {
		ToolResult result = tools().call("set_blocks", json("{\"blocks\":[{\"pos\":[0,1,2],\"block\":\"red_bed\"}],\"part\":\"bed\"}"));
		assertTrue(result.isError() && result.text().contains("can't be in a moving part"), result.text());
		ToolResult door = tools().call("set_blocks", json("{\"blocks\":[{\"pos\":[0,1,2],\"block\":\"oak_door\"}],\"part\":\"door\"}"));
		assertTrue(door.isError() && door.text().contains("can't be in a moving part"), door.text());
	}

	@Test
	void aPartIsNoReplaceFill() {
		ToolResult result = tools().call("fill", json("{\"box\":[0,1,2,2,1,3],\"block\":\"stone\",\"mode\":\"replace\",\"filter\":\"air\","
				+ "\"part\":\"wing\"}"));
		assertTrue(result.isError() && result.text().contains("replace"), result.text());
	}

	@Test
	void theMovingBudgetRefusesPastIt() {
		ToolExecutor tools = tools();
		tools.movingBudget(5);
		ToolResult tooMany = tools.call("fill", json(WING));
		assertTrue(tooMany.isError() && tooMany.text().contains("too_many") && tooMany.text().contains("5 left"), tooMany.text());
		assertEquals(0, sink.queuedCount());
		assertFalse(tools.call("fill", json("{\"box\":[0,1,2,1,1,3],\"block\":\"oak_planks\",\"part\":\"wing\"}")).isError());
		ToolResult past = tools.call("fill", json("{\"box\":[0,2,2,1,2,2],\"block\":\"oak_planks\",\"part\":\"tail\"}"));
		assertTrue(past.isError() && past.text().contains("1 left"), past.text());
		assertFalse(tools.call("set_blocks", json("{\"blocks\":[{\"pos\":[5,1,2],\"block\":\"stone\"}],\"part\":\"tail\"}")).isError());
	}

	/** A spot given again in the same part replaces its block, as a block would: it counts once, and so is one display. */
	@Test
	void aSpotCountsOnceInAPart() {
		ToolExecutor tools = tools();
		tools.movingBudget(6);
		assertFalse(tools.call("fill", json(WING)).isError());
		ToolResult again = tools.call("fill", json("{\"box\":[0,1,2,2,1,3],\"block\":\"stone\",\"part\":\"wing\"}"));
		assertFalse(again.isError(), again.text());
		assertTrue(again.text().contains("6 moving blocks in this build; 0 left"), again.text());
		assertEquals(6, tools.parts().get("wing").blocks().size());
		// Another part's display at the same spot is its own.
		ToolResult tail = tools.call("set_blocks", json("{\"blocks\":[{\"pos\":[0,1,2],\"block\":\"stone\"}],\"part\":\"tail\"}"));
		assertTrue(tail.isError() && tail.text().contains("0 left"), tail.text());
	}

	@Test
	void displaysDontCountAsChangedBlocks() {
		ToolExecutor tools = tools();
		sink.capReached = true;
		assertTrue(tools.call("fill", json("{\"box\":[0,0,2,0,0,2],\"block\":\"stone\"}")).isError());
		assertFalse(tools.call("fill", json(WING)).isError(), "a part changes no block");
	}

	/** check_motion looks over the parts as the tools know them, and the world's solid blocks once they're placed. */
	@Test
	void checkMotionLooksOverTheParts() {
		world.solid = Set.of(new Pos(0, 1, 2), new Pos(9, 9, 9));
		ToolExecutor tools = tools();
		assertTrue(tools.call("check_motion", json("{}")).text().contains("no moving parts"));
		tools.call("fill", json(WING));
		tools.call("animate", json("{\"part\":\"wing\",\"pivot\":[-0.5,1,2.5],\"motions\":[{\"spin\":{\"axis\":\"up\",\"period\":40}}]}"));
		ToolResult result = tools.call("check_motion", json("{}"));
		assertFalse(result.isError(), result.text());
		assertTrue(result.text().contains("wing (6 blocks):") && result.text().contains("as built [0, 1, 2] to [2, 1, 3]"), result.text());
		assertTrue(result.text().contains("passes through 1 solid block: [0, 1, 2]"), result.text());
		sink.pending = 500;
		assertTrue(tools.call("check_motion", json("{}")).text().contains("500 blocks are still going in"));
	}

	/** An earlier build's wing, as a refinement's tools are told of it. */
	private static final PartInfo EARLIER_WING = new PartInfo(List.of(new Pos(0, 1, 2), new Pos(1, 1, 2), new Pos(2, 1, 2), new Pos(0, 1, 3),
			new Pos(1, 1, 3), new Pos(2, 1, 3)), new MovingPart("wing", new Vec3(-0.5, 1, 2.5), List.of(new Motion.Swing("forward", -20, 45, 40, 0)),
			""));

	/** A refinement's get_context lists the moving parts it may change, with their motions (spec v2 §4.4). */
	@Test
	void aRefinementsContextListsItsParts() {
		ToolExecutor tools = tools();
		tools.refining("Dragon", Box.of(0, 0, 2, 5, 5, 9));
		Map<String, PartInfo> earlier = new LinkedHashMap<>();
		earlier.put("wing", EARLIER_WING);
		earlier.put("tail", new PartInfo(List.of(new Pos(0, 2, 8), new Pos(0, 2, 9)), null));
		tools.refiningParts(earlier);
		String context = tools.call("get_context", json("{}")).text();
		assertTrue(context.contains("Moving parts:"), context);
		assertTrue(context.contains("wing (6 blocks) moves about the pivot [-0.5, 1, 2.5]: swings about forward from -20° to 45° every 40 ticks"),
				context);
		assertTrue(context.contains("tail (2 blocks) stands still"), context);
	}

	@Test
	void aRefinementAnimatesAnEarlierPart() {
		ToolExecutor tools = tools();
		tools.refiningParts(Map.of("wing", EARLIER_WING));
		ToolResult faster = tools.call("animate", json("{\"part\":\"wing\",\"pivot\":[-0.5,1,2.5],\"motions\":[{\"swing\":{\"axis\":"
				+ "\"forward\",\"from\":-20,\"to\":45,\"period\":20}}]}"));
		assertFalse(faster.isError(), faster.text());
		assertEquals(List.of(new Motion.Swing("forward", -20, 45, 20, 0)), tools.parts().get("wing").motion().motions());
		assertEquals(1, sink.animated.size());
	}

	/** A write naming an earlier part adds to it: its blocks count, a spot it has counts once, and it keeps its motion. */
	@Test
	void aRefinementAddsToAnEarlierPart() {
		ToolExecutor tools = tools();
		tools.movingBudget(8);
		tools.refiningParts(Map.of("wing", EARLIER_WING));
		ToolResult more = tools.call("set_blocks", json("{\"blocks\":[{\"pos\":[3,1,2],\"block\":\"stone\"},{\"pos\":[3,1,3],\"block\":\"stone\"},"
				+ "{\"pos\":[0,1,2],\"block\":\"stone\"}],\"part\":\"wing\"}"));
		assertFalse(more.isError(), more.text());
		assertTrue(more.text().contains("8 moving blocks in this build; 0 left"), more.text());
		assertEquals(8, tools.parts().get("wing").blocks().size());
		assertEquals(EARLIER_WING.motion(), tools.parts().get("wing").motion(), "the motion it had");
	}

	@Test
	void animateNeedsAPart() {
		ToolResult result = tools().call("animate", json("{\"part\":\"ghost\",\"pivot\":[0,1,2],\"motions\":[{\"spin\":{\"axis\":\"up\","
				+ "\"period\":40}}]}"));
		assertTrue(result.isError() && result.text().contains("no part called \"ghost\""), result.text());
	}

	@Test
	void animateReplacesTheMotion() {
		ToolExecutor tools = tools();
		tools.call("fill", json(WING));
		ToolResult swing = tools.call("animate", json("{\"part\":\"wing\",\"pivot\":[-0.5,1,2.5],\"motions\":[{\"swing\":{\"axis\":\"forward\","
				+ "\"from\":-20,\"to\":45,\"period\":40}}]}"));
		assertFalse(swing.isError(), swing.text());
		assertTrue(swing.text().contains("wing") && swing.text().contains("-20") && swing.text().contains("45"), swing.text());
		ToolResult spin = tools.call("animate", json("{\"part\":\"wing\",\"pivot\":[-0.5,1,2.5],\"motions\":[{\"spin\":{\"axis\":\"up\","
				+ "\"period\":200}}]}"));
		assertFalse(spin.isError(), spin.text());
		assertEquals(2, sink.animated.size());
		assertEquals(List.of(new Motion.Spin("up", 200, 0)), tools.parts().get("wing").motion().motions());
		assertEquals(List.of(new Motion.Spin("up", 200, 0)), sink.animated.getLast().motions());
	}

	@Test
	void animateRefusesACycle() {
		ToolExecutor tools = tools();
		tools.call("fill", json(WING));
		tools.call("fill", json("{\"box\":[0,3,2,0,3,2],\"block\":\"stone\",\"part\":\"body\"}"));
		String spin = "\"motions\":[{\"spin\":{\"axis\":\"up\",\"period\":200}}]";
		assertFalse(tools.call("animate", json("{\"part\":\"wing\",\"pivot\":[0,1,2]," + spin + ",\"parent\":\"body\"}")).isError());
		ToolResult loop = tools.call("animate", json("{\"part\":\"body\",\"pivot\":[0,3,2]," + spin + ",\"parent\":\"wing\"}"));
		assertTrue(loop.isError() && loop.text().contains("ride"), loop.text());
		ToolResult self = tools.call("animate", json("{\"part\":\"body\",\"pivot\":[0,3,2]," + spin + ",\"parent\":\"body\"}"));
		assertTrue(self.isError() && self.text().contains("ride"), self.text());
		ToolResult stranger = tools.call("animate", json("{\"part\":\"body\",\"pivot\":[0,3,2]," + spin + ",\"parent\":\"nobody\"}"));
		assertTrue(stranger.isError() && stranger.text().contains("no part called \"nobody\""), stranger.text());
	}

	@Test
	void aPivotFarAwayIsRefused() {
		ToolExecutor tools = tools();
		tools.call("fill", json(WING));
		ToolResult far = tools.call("animate", json("{\"part\":\"wing\",\"pivot\":[500,1,2],\"motions\":[{\"spin\":{\"axis\":\"up\",\"period\":200}}]}"));
		assertTrue(far.isError() && far.text().contains("pivot"), far.text());
		ToolResult none = tools.call("animate", json("{\"part\":\"wing\",\"pivot\":[0,1,2],\"motions\":[]}"));
		assertTrue(none.isError() && none.text().contains("motions"), none.text());
		ToolResult bad = tools.call("animate", json("{\"part\":\"wing\",\"pivot\":[0,1,2],\"motions\":[{\"spin\":{\"axis\":\"north\",\"period\":200}}]}"));
		assertTrue(bad.isError() && bad.text().contains("forward, back, left, right, up or down"), bad.text());
	}
}
