package io.palmisano.aibuilder.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.palmisano.aibuilder.core.blocks.FakeCatalog;
import io.palmisano.aibuilder.core.blocks.Validator;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.Placement;
import io.palmisano.aibuilder.core.shapes.Box;
import org.junit.jupiter.api.Test;

class ToolExecutorTest {
	static final String BRIEF = "{\"title\":\"test house\",\"style\":\"plain\"}";

	private final RecordingSink sink = new RecordingSink();

	private ToolExecutor executor(int budget, long maxChangedBlocks) {
		return new ToolExecutor(new Validator(FakeCatalog.standard(), Validator.DEFAULT_BANNED),
				new LocalFrame(new Pos(0, 64, 0), Facing.SOUTH), Box.of(-8, -2, 0, 7, 15, 15), maxChangedBlocks, sink, new Budget(budget));
	}

	private ToolExecutor briefed() {
		ToolExecutor tools = executor(150, 100_000);
		tools.call("submit_brief", json(BRIEF));
		return tools;
	}

	static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	@Test
	void writesNeedABriefFirst() {
		ToolResult result = executor(150, 100_000).call("fill", json("{\"box\":[0,0,0,1,0,0],\"block\":\"stone\"}"));
		assertTrue(result.isError());
		assertTrue(result.text().contains("submit_brief"), result.text());
		assertEquals(0, sink.queuedCount());
	}

	@Test
	void fillQueuesWorldPositionsThroughTheFrame() {
		ToolResult result = briefed().call("fill", json("{\"box\":[0,0,0,1,0,0],\"block\":\"stone\"}"));
		assertFalse(result.isError(), result.text());
		assertTrue(result.text().startsWith("Queued 2 blocks."), result.text());
		assertEquals(List.of(new Pos(0, 64, 0), new Pos(-1, 64, 0)),
				sink.batches.getFirst().stream().map(Placement::pos).toList());
	}

	@Test
	void rejectedEntriesAreReportedAndTheRestGoThrough() {
		ToolResult result = briefed().call("set_blocks", json("{\"blocks\":["
				+ "{\"pos\":[0,0,1],\"block\":\"stone\"},"
				+ "{\"pos\":[1,0,1],\"block\":\"oak_stair\"},"
				+ "{\"pos\":[0,0,-1],\"block\":\"stone\"}]}"));
		assertEquals(1, sink.queuedCount());
		assertTrue(result.text().contains("unknown_block") && result.text().contains("minecraft:oak_stairs")
				&& result.text().contains("out_of_bounds"), result.text());
	}

	@Test
	void outsideTheBoxIsRejected() {
		ToolResult result = briefed().call("fill", json("{\"box\":[0,14,0,0,20,0],\"block\":\"stone\"}"));
		assertEquals(2, sink.queuedCount());
		assertTrue(result.text().startsWith("Queued 2 blocks.") && result.text().contains("out_of_bounds"), result.text());
	}

	@Test
	void aCallThatQueuesNothingIsAnError() {
		ToolResult result = briefed().call("fill", json("{\"box\":[0,30,0,1,30,0],\"block\":\"stone\"}"));
		assertTrue(result.isError(), result.text());
		assertTrue(result.text().startsWith("Queued 0 blocks.") && result.text().contains("out_of_bounds"), result.text());
		assertEquals(0, sink.queuedCount());
	}

	@Test
	void aBriefWithAMalformedPhaseIsABadArgument() {
		ToolResult result = executor(150, 100_000).call("submit_brief",
				json("{\"title\":\"test house\",\"style\":\"plain\",\"phases\":[{\"name\":\"walls\"}]}"));
		assertTrue(result.isError(), result.text());
		assertTrue(result.text().contains("bad_argument") && result.text().contains("phases"), result.text());
	}

	@Test
	void setBlocksStopsAtTwoThousand() {
		String blocks = IntStream.range(0, 2001).mapToObj(i -> "{\"pos\":[0,0,1],\"block\":\"stone\"}").collect(Collectors.joining(","));
		ToolResult result = briefed().call("set_blocks", json("{\"blocks\":[" + blocks + "]}"));
		assertTrue(result.isError());
		assertTrue(result.text().contains("too_many"), result.text());
		assertEquals(0, sink.queuedCount());
	}

	@Test
	void changedBlockCapCutsOffTheRest() {
		ToolExecutor tools = executor(150, 5);
		tools.call("submit_brief", json(BRIEF));
		ToolResult result = tools.call("fill", json("{\"box\":[0,0,0,7,0,0],\"block\":\"stone\"}"));
		assertEquals(5, sink.queuedCount());
		assertTrue(result.text().contains("too_many"), result.text());
	}

	@Test
	void checkpointWaitsForTheSinkAndReports() {
		ToolResult result = briefed().call("checkpoint", json("{\"phase\":\"walls\"}"));
		assertEquals(List.of("walls"), sink.checkpoints);
		assertEquals("Checkpoint walls: 42 blocks placed so far.", result.text());
	}

	@Test
	void finishEndsTheBuild() {
		ToolExecutor tools = briefed();
		tools.call("finish", json("{\"summary\":\"a small house\"}"));
		assertTrue(tools.finished());
		assertEquals("a small house", sink.finishedWith);
		ToolResult after = tools.call("fill", json("{\"box\":[0,0,0,1,0,0],\"block\":\"stone\"}"));
		assertTrue(after.isError());
		assertEquals("This build is finished.", after.text());
	}

	@Test
	void budgetFootersAppearNearTheEnd() {
		ToolExecutor tools = executor(20, 100_000);
		String fill = "{\"box\":[0,0,0,0,0,0],\"block\":\"stone\"}";
		tools.call("submit_brief", json(BRIEF));
		for (int call = 2; call <= 15; call++) {
			tools.call("fill", json(fill));
		}
		assertTrue(tools.call("fill", json(fill)).text().endsWith("Budget: 4 tool calls left."));
		tools.call("fill", json(fill));
		tools.call("fill", json(fill));
		assertTrue(tools.call("fill", json(fill)).text().endsWith("Budget nearly spent: finish now."));
		tools.call("fill", json(fill));
		ToolResult over = tools.call("fill", json(fill));
		assertTrue(over.isError());
		assertEquals("Budget reached: call finish now.", over.text());
		tools.call("finish", json("{\"summary\":\"done\"}"));
		assertTrue(tools.finished());
	}

	@Test
	void badArgumentsNameTheArgument() {
		ToolResult result = briefed().call("fill", json("{\"block\":\"stone\"}"));
		assertTrue(result.isError());
		assertTrue(result.text().contains("bad_argument") && result.text().contains("box"), result.text());
	}

	@Test
	void roofProblemsComeBackAsBadArgument() {
		ToolResult result = briefed().call("roof", json("{\"box\":[0,4,1,4,4,3],\"style\":\"gable\",\"stairs\":\"oak_stairs\",\"ridge\":\"z\"}"));
		assertTrue(result.isError());
		assertTrue(result.text().contains("bad_argument"), result.text());
	}
}
