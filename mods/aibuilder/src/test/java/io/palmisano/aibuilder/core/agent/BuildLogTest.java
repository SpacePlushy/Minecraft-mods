package io.palmisano.aibuilder.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.palmisano.aibuilder.core.tools.ToolResult;
import org.junit.jupiter.api.Test;

class BuildLogTest {
	private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	private final List<String> lines = new ArrayList<>();
	private final Scrub scrub = new Scrub();
	private long now = Instant.parse("2026-10-22T07:00:00Z").toEpochMilli();
	private final BuildLog log = new BuildLog(lines::add, scrub, () -> now);

	@Test
	void argumentsAreShortened() {
		String blocks = IntStream.range(0, 25).mapToObj(i -> "{\"pos\":[" + i + ",0,0],\"block\":\"stone\"}").collect(Collectors.joining(","));
		String pages = IntStream.range(0, 30).mapToObj(i -> "\"page " + i + "\"").collect(Collectors.joining(","));
		JsonObject args = JsonParser.parseString("{\"blocks\":[" + blocks + "],\"box\":[0,0,0,1,1,1],\"data\":{\"pages\":[" + pages + "]}}")
				.getAsJsonObject();
		log.call("set_blocks", args, new ToolResult("Queued 25 blocks.", false), 3);

		JsonObject logged = parse(lines.getFirst()).getAsJsonObject("args");
		JsonArray shortBlocks = logged.getAsJsonArray("blocks");
		assertEquals(21, shortBlocks.size());
		assertEquals(JsonParser.parseString("{\"pos\":[19,0,0],\"block\":\"stone\"}"), shortBlocks.get(19));
		assertEquals("… 5 more", shortBlocks.get(20).getAsString());
		assertEquals(JsonParser.parseString("[0,0,0,1,1,1]"), logged.get("box"));
		JsonArray shortPages = logged.getAsJsonObject("data").getAsJsonArray("pages");
		assertEquals(21, shortPages.size());
		assertEquals("… 10 more", shortPages.get(20).getAsString());
		// The executor's own arguments are left alone.
		assertEquals(25, args.getAsJsonArray("blocks").size());
	}

	@Test
	void everyCallIsALine() {
		log.request("a tiny hut", "Small", false, "", "claude-opus-5-5", "high", "ea63cf99-ab48-456c-8496-0097e064226d");
		log.started("2.1.306", "claude-opus-5-5");
		log.call("get_context", new JsonObject(), new ToolResult("Frame: you build in a local frame.\nSize: Small (24 blocks)", false), 5);
		now += 1500;
		JsonObject fill = JsonParser.parseString("{\"box\":[-1,0,1,1,2,3],\"block\":\"oak_planks\"}").getAsJsonObject();
		log.call("fill", fill, new ToolResult("Queued 10 blocks. Rejected 2:\n2 blocks — protected: something player-made is there\nBudget: 140 calls left",
				false, List.of("2 blocks — protected: something player-made is there")), 7);
		log.call("roof", new JsonObject(), new ToolResult("Call submit_brief first: title, style, size, palette and phases.", true), 1);
		log.call("finish", null, new ToolResult("Build finished.", false), 0);
		log.usage(2, 62, 674, 642);
		log.usage(4, 73, 1860, 772);
		log.outcome("done", "Done: 10 blocks placed.", 10, 0);

		assertEquals(List.of("request", "claude", "tool", "tool", "tool", "tool", "usage", "usage", "outcome", "totals"),
				lines.stream().map(line -> parse(line).get("type").getAsString()).toList());
		JsonObject request = parse(lines.getFirst());
		assertEquals("a tiny hut", request.get("prompt").getAsString());
		assertEquals("new", request.get("mode").getAsString());
		assertEquals("Small", request.get("size").getAsString());
		assertEquals("claude-opus-5-5", request.get("model").getAsString());
		assertEquals("2026-10-22T07:00:00Z", request.get("time").getAsString());
		assertEquals("2.1.306", parse(lines.get(1)).get("claude_code_version").getAsString());

		JsonObject filled = parse(lines.get(3));
		assertEquals("fill", filled.get("name").getAsString());
		assertEquals(fill, filled.get("args"));
		assertEquals("Queued 10 blocks. Rejected 2:", filled.get("result").getAsString());
		assertEquals(JsonParser.parseString("[\"2 blocks — protected: something player-made is there\"]"), filled.get("rejections"));
		assertFalse(filled.get("error").getAsBoolean());
		assertEquals(7, filled.get("ms").getAsLong());
		assertEquals("2026-10-22T07:00:01.500Z", filled.get("time").getAsString());
		JsonObject refused = parse(lines.get(4));
		assertTrue(refused.get("error").getAsBoolean());
		assertFalse(refused.has("rejections"));
		assertEquals(new JsonObject(), parse(lines.get(5)).get("args"));

		JsonObject outcome = parse(lines.get(8));
		assertEquals("done", outcome.get("outcome").getAsString());
		assertEquals("Done: 10 blocks placed.", outcome.get("line").getAsString());
		assertEquals(10, outcome.get("placed").getAsInt());
		JsonObject totals = parse(lines.getLast());
		assertEquals(4, totals.get("tool_calls").getAsInt());
		assertEquals(1, totals.get("refused_calls").getAsInt());
		assertEquals(2, totals.get("turns").getAsInt());
		assertEquals(6, totals.get("input_tokens").getAsLong());
		assertEquals(135, totals.get("output_tokens").getAsLong());
		assertEquals(2534, totals.get("cache_read_tokens").getAsLong());
		assertEquals(1414, totals.get("cache_write_tokens").getAsLong());
		assertEquals(1.5, totals.get("seconds").getAsDouble());
	}

	@Test
	void aRefinementSaysWhatItRefines() {
		log.request("add a moat", "Large", true, "Stonehold Castle", "claude-opus-5-5", "high", "ea63cf99-ab48-456c-8496-0097e064226d");
		JsonObject request = parse(lines.getFirst());
		assertEquals("refine", request.get("mode").getAsString());
		assertEquals("Stonehold Castle", request.get("refines").getAsString());
		assertEquals("ea63cf99-ab48-456c-8496-0097e064226d", request.get("session").getAsString());
	}

	@Test
	void linesAreScrubbed() {
		scrub.add(TOKEN);
		log.request("a hut; my key is sk-ant-api03-SECRETSECRETSECRET", "Small", false, "", "claude-opus-5-5", "high", "s");
		JsonObject args = new JsonObject();
		args.addProperty("summary", "Built it. Authorization: Bearer " + TOKEN);
		log.call("finish", args, new ToolResult("Build finished. " + TOKEN, false), 1);
		assertTrue(lines.stream().noneMatch(line -> line.contains(TOKEN) || line.contains("SECRETSECRET")), lines.toString());
		assertTrue(lines.stream().allMatch(line -> line.contains("[redacted]")), lines.toString());
		lines.forEach(BuildLogTest::parse);
	}

	@Test
	void aLongResultIsCut() {
		log.call("survey_area", new JsonObject(), new ToolResult("x".repeat(5000), false), 40);
		String result = parse(lines.getFirst()).get("result").getAsString();
		assertEquals(BuildLog.RESULT_LIMIT, result.codePointCount(0, result.length()));
		assertTrue(result.endsWith("…"));
	}

	@Test
	void fileNamesAreSafe() {
		LocalDateTime time = LocalDateTime.of(2026, 10, 22, 7, 58, 1);
		assertEquals("2026-10-22_07-58-01_Frankie_a-medieval-stone-castle-with-a-gatehouse",
				BuildLog.baseName(time, "Frankie", "A medieval stone castle, with a gatehouse!"));
		assertEquals("2026-10-22_07-58-01_Frankie_cafe-chateau", BuildLog.baseName(time, "Frankie", "Café château"));
		assertEquals("2026-10-22_07-58-01_a_b_build", BuildLog.baseName(time, "a/b", "???"));
		String slug = BuildLog.slug("tower ".repeat(30));
		assertTrue(slug.length() <= 40 && !slug.endsWith("-"), slug);
	}

	private static JsonObject parse(String line) {
		JsonElement element = JsonParser.parseString(line);
		assertTrue(element.isJsonObject(), line);
		assertFalse(line.contains("\n"), "a log line must be one line: " + line);
		return element.getAsJsonObject();
	}
}
