package io.palmisano.aibuilder.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import io.palmisano.aibuilder.core.request.SizeTier;
import org.junit.jupiter.api.Test;

/** config/aibuilder.json (spec §14): defaults, and a bad value falls back to its default with a warning. */
class AiBuilderConfigTest {
	@Test
	void defaultsWhenMissing() {
		AiBuilderConfig.Loaded loaded = AiBuilderConfig.load("{}");
		assertEquals(AiBuilderConfig.DEFAULT, loaded.config());
		assertEquals(List.of(), loaded.warnings());
		AiBuilderConfig c = loaded.config();
		assertEquals("claude_code", c.backend());
		assertEquals("claude-opus-5-5", c.model());
		assertEquals("high", c.effort());
		assertEquals("", c.claudePath());
		assertEquals(15_000, c.sizeLimit(SizeTier.SMALL));
		assertEquals(1_000_000, c.sizeLimit(SizeTier.EPIC));
		assertEquals(250, c.blocksPerTick());
		assertEquals(150, c.maxToolIterations());
		assertEquals(500_000, c.maxTokensPerBuild());
		assertEquals(45, c.maxBuildMinutes());
		assertTrue(c.requireOp());
		assertEquals(2, c.opLevel());
		assertEquals(List.of(), c.allowedPlayers());
		assertEquals(20, c.rateLimitPerHour());
		assertEquals(5, c.undoHistorySize());
		assertTrue(c.protectPlayerBlocks());
		assertEquals(3, c.siteGap());
		assertTrue(c.bannedBlocks().contains("minecraft:tnt"));
	}

	/** M6 Review Focus: nonsense in a hand-edited file loads as defaults for those keys and keeps the rest. */
	@Test
	void badValuesFallBack() {
		AiBuilderConfig.Loaded loaded = AiBuilderConfig.load("""
				{"blocks_per_tick": "fast", "max_tool_iterations": -3, "backend": "openai", "effort": "huge", "op_level": 9,
				 "size_limits": {"small": 0, "medium": 50000}, "require_op": "yes", "site_gap": 5}""");
		AiBuilderConfig c = loaded.config();
		assertEquals(250, c.blocksPerTick());
		assertEquals(150, c.maxToolIterations());
		assertEquals("claude_code", c.backend());
		assertEquals("high", c.effort());
		assertEquals(2, c.opLevel());
		assertEquals(15_000, c.sizeLimit(SizeTier.SMALL));
		assertEquals(50_000, c.sizeLimit(SizeTier.MEDIUM));
		assertTrue(c.requireOp());
		assertEquals(5, c.siteGap());
		for (String key : List.of("blocks_per_tick", "max_tool_iterations", "backend", "effort", "op_level", "size_limits.small", "require_op")) {
			assertTrue(loaded.warnings().stream().anyMatch(w -> w.contains(key)), key + " not warned: " + loaded.warnings());
		}
		assertEquals(7, loaded.warnings().size(), loaded.warnings().toString());
	}

	@Test
	void brokenJsonIsAllDefaults() {
		AiBuilderConfig.Loaded loaded = AiBuilderConfig.load("{\"blocks_per_tick\": 5,");
		assertEquals(AiBuilderConfig.DEFAULT, loaded.config());
		assertEquals(1, loaded.warnings().size());
		assertEquals(AiBuilderConfig.DEFAULT, AiBuilderConfig.load("[1, 2]").config());
	}

	@Test
	void unknownKeysAreWarned() {
		AiBuilderConfig.Loaded loaded = AiBuilderConfig.load("{\"blokcs_per_tick\": 5, \"_comment\": \"mine\"}");
		assertEquals(AiBuilderConfig.DEFAULT, loaded.config());
		assertEquals(1, loaded.warnings().size());
		assertTrue(loaded.warnings().getFirst().contains("blokcs_per_tick"), loaded.warnings().toString());
	}

	/** The file written on the first run reads back as the defaults, and says who allowed_players is for (spec §15). */
	@Test
	void theDefaultFileRoundTrips() {
		String text = AiBuilderConfig.DEFAULT.toJson();
		AiBuilderConfig.Loaded loaded = AiBuilderConfig.load(text);
		assertEquals(AiBuilderConfig.DEFAULT, loaded.config());
		assertEquals(List.of(), loaded.warnings());
		assertTrue(text.contains("\"_comment_allowed_players\""), text);
		assertTrue(text.contains("only yourself"), text);
	}

	@Test
	void bannedBlocksMustBeIds() {
		AiBuilderConfig.Loaded loaded = AiBuilderConfig.load("{\"banned_blocks\": [\"minecraft:tnt\", \"not an id\", 5, \"lava\"]}");
		assertEquals(List.of("minecraft:tnt", "minecraft:lava"), loaded.config().bannedBlocks());
		assertEquals(2, loaded.warnings().size(), loaded.warnings().toString());
	}

	/** animate_build off raises the rate; the per-tick time cap still holds (spec §9). */
	@Test
	void animateBuildOffPlacesFaster() {
		assertEquals(250, AiBuilderConfig.DEFAULT.placementRate());
		assertEquals(250 * AiBuilderConfig.UNANIMATED_FACTOR, AiBuilderConfig.load("{\"animate_build\": false}").config().placementRate());
	}

	@Test
	void allowedPlayersAreNamesOrIds() {
		AiBuilderConfig c = AiBuilderConfig.load("{\"allowed_players\": [\"Frankie\", \"0f6a1c2e-0000-4000-8000-000000000001\", \"\"]}").config();
		assertEquals(List.of("Frankie", "0f6a1c2e-0000-4000-8000-000000000001"), c.allowedPlayers());
	}
}
