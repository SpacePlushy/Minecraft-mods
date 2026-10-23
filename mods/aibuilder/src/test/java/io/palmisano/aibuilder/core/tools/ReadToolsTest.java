package io.palmisano.aibuilder.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
import io.palmisano.aibuilder.core.survey.BuildProblems;
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
		assertEquals(22, ToolExecutor.handledTools().size());
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

	/** On a refinement get_context names the build and where its changes are so far (spec §8). */
	@Test
	void aRefinementsContextNamesItsBuild() {
		ToolExecutor tools = tools();
		tools.refining("Stonehold Castle", Box.of(-5, 0, 2, 5, 12, 14));
		String text = tools.call("get_context", json("{}")).text();
		assertTrue(text.contains("\nRefining: Stonehold Castle, the build this session made: its blocks fill x -5..5, y 0..12, z 2..14;"), text);
		assertTrue(text.contains("y=0 is the ground at the origin, where this build began."), text);
		assertFalse(text.contains("in front of the player"), text);
	}

	/** The origin is site_gap ahead of the player, as the config sets it. */
	@Test
	void theSiteGapIsTheConfigs() {
		ToolExecutor tools = tools();
		tools.siteGap(7);
		String text = tools.call("get_context", json("{}")).text();
		assertTrue(text.contains("y=0 is the ground at the origin, 7 blocks in front of the player."), text);
		assertFalse(text.contains("Refining"), text);
	}

	/** The default radius is half the tier's size plus 8, centred on the box's footprint. */
	@Test
	void surveyUsesTheSnapshot() {
		String text = tools().call("survey_area", json("{}")).text();
		assertEquals("terrain -20 -8 41 41", world.requests.getFirst());
		assertTrue(text.contains("\nSurvey: 41×41 columns"), text);
	}

	/** Mid-build, the site survey is still the one read as the build began: the text says so. */
	@Test
	void aSurveySaysWhenItWasRead() {
		String text = tools().call("survey_area", json("{}")).text();
		assertTrue(text.startsWith("As the site was when this build began: blocks it has placed since aren't in it (inspect_region shows"
				+ " blocks as they are now)."), text.lines().findFirst().orElse(""));
	}

	/** In a refinement the player may stand inside the build: the survey says where (spec §5.5). */
	@Test
	void aSurveySaysWhereThePlayerIs() {
		String first = tools().call("survey_area", json("{}")).text().lines().findFirst().orElse("");
		assertTrue(first.endsWith(" The player stands at x 0, y 1, z -3."), first);
	}

	/** The owner offline, or in another dimension: no position, rather than one made up. */
	@Test
	void noPlayerIsNoPosition() {
		world.facts = new WorldFacts("minecraft:overworld", "minecraft:plains", "morning", "clear", "minecraft:grass_block", null);
		String context = tools().call("get_context", json("{}")).text();
		assertTrue(context.contains("\nThe player isn't in this world right now.\n"), context);
		assertFalse(context.contains("stands at"), context);
		String survey = tools().call("survey_area", json("{}")).text().lines().findFirst().orElse("");
		assertTrue(survey.endsWith(" The player isn't in this world right now."), survey);
	}

	/** A line of text a row: a tall, narrow box would run past what one read returns. */
	@Test
	void aRegionTooLongToReadIsRefused() {
		ToolResult result = tools().call("inspect_region", json("{\"box\":[0,-2048,1,0,2047,1]}"));
		String first = result.text().lines().findFirst().orElse("");
		assertTrue(result.isError() && first.startsWith("too_many: "), first);
		assertTrue(first.contains("look at it in parts"), first);
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

	/** A box far from the site would make the server load or generate chunks on the spot: refused before reading. */
	@Test
	void inspectRegionFarAwayIsRefused() {
		ToolExecutor tools = tools();
		ToolResult far = tools.call("inspect_region", json("{\"box\":[0,0,4000,0,0,4095]}"));
		assertTrue(far.isError() && far.text().contains("out_of_bounds") && far.text().contains("96 blocks"), far.text());
		ToolResult side = tools.call("inspect_region", json("{\"box\":[108,0,0,108,0,0]}"));
		assertTrue(side.isError(), side.text());
		assertTrue(world.requests.isEmpty(), "nothing read for a refused call");
		// The edge of what may be read: 96 beyond the allowed box (x -12..11, z 0..23).
		assertFalse(tools.call("inspect_region", json("{\"box\":[107,0,-96,107,0,-96]}")).isError());
		assertFalse(tools.call("inspect_region", json("{\"box\":[-108,-40,119,-108,40,119]}")).isError());
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

	/** One terraform call could otherwise make millions of blocks in memory (a target far above or below the box). */
	@Test
	void terraformRefusesATargetOutsideTheBox() {
		ToolExecutor tools = tools();
		tools.call("submit_brief", json("{\"title\":\"site\",\"style\":\"flat\"}"));
		for (int target : new int[] {1_000_000, 24, -7, -1_000_000}) {
			ToolResult result = tools.call("terraform", json("{\"box\":[-12,0,0,11,0,23],\"target_y\":" + target + ",\"surface_block\":\"oak_planks\"}"));
			assertTrue(result.isError() && result.text().contains("out_of_bounds") && result.text().contains("-6..23"), target + ": " + result.text());
		}
		assertEquals(0, sink.queuedCount());
		assertTrue(world.requests.isEmpty(), "nothing read for a refused call");
	}

	/** A column taller or deeper than the box is levelled only inside it. */
	@Test
	void terraformStaysInsideTheBox() {
		world.current = (x, z) -> x == 0 ? new Column(0, 40, Column.NONE, "minecraft:stone", 0, 0, false, 0, "")
				: new Column(-20, -20, Column.NONE, "minecraft:stone", 0, 0, false, 0, "");
		ToolExecutor tools = tools();
		tools.call("submit_brief", json("{\"title\":\"site\",\"style\":\"flat\"}"));
		tools.call("terraform", json("{\"box\":[0,0,1,1,0,1],\"target_y\":0,\"surface_block\":\"oak_planks\",\"fill_block\":\"stone\"}"));
		int highest = sink.batches.getFirst().stream().mapToInt(p -> p.pos().y() - 64).max().orElseThrow();
		int lowest = sink.batches.getFirst().stream().mapToInt(p -> p.pos().y() - 64).min().orElseThrow();
		assertEquals(23, highest, "air only up to the box's top");
		assertEquals(-6, lowest, "fill only from the box's bottom");
	}

	/** Over a drop with no floor in reach, the fill comes up from the box's bottom. */
	@Test
	void terraformFillsAColumnWithNoFloorFromTheBoxBottom() {
		world.current = (x, z) -> new Column(Column.NONE, Column.NONE, Column.NONE, "minecraft:air", 0, 0, false, 0, "");
		ToolExecutor tools = tools();
		tools.call("submit_brief", json("{\"title\":\"site\",\"style\":\"flat\"}"));
		tools.call("terraform", json("{\"box\":[0,0,1,0,0,1],\"target_y\":-4,\"surface_block\":\"oak_planks\",\"fill_block\":\"stone\"}"));
		Map<Integer, String> column = new TreeMap<>();
		sink.batches.getFirst().forEach(p -> column.put(p.pos().y() - 64, p.block().info().id()));
		assertEquals(Map.of(-6, "minecraft:stone", -5, "minecraft:stone", -4, "minecraft:oak_planks"), column);
	}

	/**
	 * Terraform works from the ground as it is now, after what's queued is placed: a site levelled to 0 and then
	 * raised to 2 is solid under its new surface (spec §8: "a flat site, a terrace or a raised plinth").
	 */
	@Test
	void terraformReadsTheWorldAsItIsNow() {
		// When the build started the column stood at 3; an earlier terraform has since levelled it to 0.
		world.columns = (x, z) -> new Column(3, 3, Column.NONE, "minecraft:grass_block", 0, 0, false, 0, "");
		world.current = (x, z) -> new Column(0, 0, Column.NONE, "minecraft:oak_planks", 0, 0, false, 0, "");
		sink.onAwait = () -> world.requests.add("awaitPlaced");
		ToolExecutor tools = tools();
		tools.call("submit_brief", json("{\"title\":\"site\",\"style\":\"flat\"}"));
		tools.call("terraform", json("{\"box\":[0,0,1,0,0,1],\"target_y\":2,\"surface_block\":\"oak_planks\",\"fill_block\":\"stone\"}"));
		Map<Integer, String> column = new TreeMap<>();
		sink.batches.getFirst().forEach(p -> column.put(p.pos().y() - 64, p.block().info().id()));
		assertEquals(Map.of(1, "minecraft:stone", 2, "minecraft:oak_planks"), column);
		// What's queued goes in first, then the ground is read again.
		assertEquals(List.of("awaitPlaced", "currentTerrain 0 1 1 1"), world.requests);
	}

	/** check_build waits for what's queued, then lists what the world check found (spec §8). */
	@Test
	void checkBuildWaitsThenLists() {
		sink.onAwait = () -> world.requests.add("awaitPlaced");
		world.problems = List.of(new BuildProblems.Problem(BuildProblems.Kind.DARK, new Pos(0, 1, 2), ""));
		ToolExecutor tools = tools();
		tools.call("submit_brief", json("{\"title\":\"site\",\"style\":\"flat\"}"));
		ToolResult result = tools.call("check_build", json("{}"));
		assertFalse(result.isError(), result.text());
		assertTrue(result.text().contains("dark (1)") && result.text().contains("(0, 1, 2)"), result.text());
		assertEquals(List.of("awaitPlaced", "check"), world.requests);
	}

	@Test
	void checkBuildNeedsTheBrief() {
		ToolResult result = tools().call("check_build", json("{}"));
		assertTrue(result.isError() && result.text().contains("submit_brief"), result.text());
	}

	/** Placement still behind after the wait: check again later, rather than check half a build. */
	@Test
	void checkBuildWhilePlacementIsBehind() {
		sink.pending = 500;
		ToolExecutor tools = tools();
		tools.call("submit_brief", json("{\"title\":\"site\",\"style\":\"flat\"}"));
		ToolResult result = tools.call("check_build", json("{}"));
		assertTrue(result.text().contains("500 blocks are still going in"), result.text());
		assertFalse(world.requests.contains("check"), world.requests.toString());
	}
}
