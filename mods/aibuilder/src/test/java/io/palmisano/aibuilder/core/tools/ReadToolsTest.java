package io.palmisano.aibuilder.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.palmisano.aibuilder.core.blocks.FakeCatalog;
import io.palmisano.aibuilder.core.blocks.Validator;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.Placement;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.survey.Column;
import org.junit.jupiter.api.Test;

/** The tools Claude looks with, and terraform, which levels a site from what it saw (spec §8, §11). */
class ReadToolsTest {
	private final RecordingSink sink = new RecordingSink();
	private final FakeWorld world = new FakeWorld();

	/** Small tier (24): the allowed box x -12..11, y -6..23, z 0..23; frame origin (0, 64, 0) facing south. */
	private ToolExecutor tools() {
		return new ToolExecutor(new Validator(FakeCatalog.standard(), Validator.DEFAULT_BANNED), new LocalFrame(new Pos(0, 64, 0), Facing.SOUTH),
				Box.of(-12, -6, 0, 11, 23, 23), SizeTier.SMALL, 15_000, sink, new Budget(150), world);
	}

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	@Test
	void everyCatalogToolHasAHandlerAndBack() {
		assertEquals(19, ToolExecutor.handledTools().size());
		assertEquals(ToolExecutor.handledTools(), ToolCatalog.load().names());
	}

	/** Looking around comes before the brief. */
	@Test
	void readToolsNeedNoBrief() {
		ToolExecutor tools = tools();
		for (String call : new String[] {"get_context", "survey_area", "search_blocks"}) {
			ToolResult result = tools.call(call, json(call.equals("search_blocks") ? "{\"query\":\"oak\"}" : "{}"));
			assertFalse(result.isError(), call + ": " + result.text());
		}
	}

	@Test
	void contextTellsTheFrameAndTheBox() {
		String text = tools().call("get_context", json("{}")).text();
		assertTrue(text.contains("+x is the player's right, +z is forward"), text);
		assertTrue(text.contains("Small (24 blocks): the allowed box is x -12..11, y -6..23, z 0..23"), text);
		assertTrue(text.contains("minecraft:overworld, biome minecraft:plains, morning, clear"), text);
		assertTrue(text.contains("The player stands at x 0, y 1, z -3."), text);
		assertTrue(text.contains("149 of 150 tool calls left"), text);
	}

	/** The default radius is half the tier's size plus 8, centred on the box's footprint. */
	@Test
	void surveyUsesTheSnapshot() {
		String text = tools().call("survey_area", json("{}")).text();
		assertEquals("terrain -20 -8 41 41", world.requests.getFirst());
		assertTrue(text.startsWith("Survey: 41×41 columns"), text);
	}

	@Test
	void surveyRadiusIsCapped() {
		tools().call("survey_area", json("{\"radius\":500}"));
		assertEquals("terrain -96 -84 193 193", world.requests.getFirst());
	}

	@Test
	void inspectRegionIsCappedAt4096() {
		ToolExecutor tools = tools();
		ToolResult tooBig = tools.call("inspect_region", json("{\"box\":[0,0,0,16,15,15]}"));
		assertTrue(tooBig.isError() && tooBig.text().contains("too_many") && tooBig.text().contains("4,096"), tooBig.text());
		assertTrue(world.requests.isEmpty(), "nothing read for a refused call");
		ToolResult ok = tools.call("inspect_region", json("{\"box\":[0,0,0,1,1,0]}"));
		assertTrue(ok.text().startsWith("Region x 0..1, y 0..1, z 0..0 (local): 4 blocks"), ok.text());
	}

	@Test
	void searchListsProperties() {
		String text = tools().call("search_blocks", json("{\"query\":\"oak stairs\"}")).text();
		assertTrue(text.contains("oak_stairs: facing back|forward|right|left; half top|bottom"), text);
	}

	/**
	 * Columns x 0..3 at z 1: a hill (floor 3), a dip (floor -2), flat ground (floor 0) and a tree (floor 0, top 5).
	 * Levelled to 0: the hill and the tree are cut away, the dip filled, planks on top everywhere (the test catalog
	 * has no grass).
	 */
	@Test
	void terraformCutsAndFills() {
		world.columns = (x, z) -> switch (x) {
			case 0 -> new Column(3, 3, Column.NONE, "minecraft:grass_block", 0, 0, false, 0, "");
			case 1 -> new Column(-2, -2, Column.NONE, "minecraft:grass_block", 0, 0, false, 0, "");
			case 3 -> new Column(0, 5, Column.NONE, "minecraft:grass_block", 0, 0, true, 0, "");
			default -> new Column(0, 0, Column.NONE, "minecraft:grass_block", 0, 0, false, 0, "");
		};
		ToolExecutor tools = tools();
		tools.call("submit_brief", json("{\"title\":\"site\",\"style\":\"flat\"}"));
		ToolResult result = tools.call("terraform", json(
				"{\"box\":[0,-5,1,3,10,1],\"target_y\":0,\"surface_block\":\"oak_planks\",\"fill_block\":\"stone\"}"));
		assertFalse(result.isError(), result.text());
		Map<String, String> placed = new TreeMap<>();
		for (Placement p : sink.batches.getFirst()) {
			// Facing south, local x is world -x; y is world y - 64; z is the same.
			placed.put((-p.pos().x()) + "," + (p.pos().y() - 64), p.block().info().id());
		}
		assertEquals(new TreeMap<>(Map.ofEntries(
				Map.entry("0,0", "minecraft:oak_planks"), Map.entry("0,1", "minecraft:air"), Map.entry("0,2", "minecraft:air"),
				Map.entry("0,3", "minecraft:air"),
				Map.entry("1,-1", "minecraft:stone"), Map.entry("1,0", "minecraft:oak_planks"),
				Map.entry("2,0", "minecraft:oak_planks"),
				Map.entry("3,0", "minecraft:oak_planks"), Map.entry("3,1", "minecraft:air"), Map.entry("3,2", "minecraft:air"),
				Map.entry("3,3", "minecraft:air"), Map.entry("3,4", "minecraft:air"), Map.entry("3,5", "minecraft:air"))),
				placed);
	}
}
