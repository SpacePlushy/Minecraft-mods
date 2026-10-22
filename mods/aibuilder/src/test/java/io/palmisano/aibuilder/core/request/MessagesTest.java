package io.palmisano.aibuilder.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.request.BuildPrompt.Problem;
import io.palmisano.aibuilder.core.tools.Brief;
import org.junit.jupiter.api.Test;

class MessagesTest {
	@Test
	void acceptedSaysWhatsComing() {
		assertEquals("AI Builder: building \"castle\" (Small). Claude plans before it builds, so give it a few minutes; /aibuild cancel stops it.",
				Messages.accepted("castle", SizeTier.SMALL));
	}

	@Test
	void acceptedKeepsNonAsciiIntact() {
		assertTrue(Messages.accepted("日本庭園 with a koi pond 🐟", SizeTier.AUTO).startsWith("AI Builder: building \"日本庭園 with a koi pond 🐟\" (Auto)."));
	}

	@Test
	void cappedSaysWhichLimit() {
		assertEquals("Stopped at the 150 tool-call limit: 2,314 blocks placed.", Messages.capped("the 150 tool-call limit", 2314));
	}

	@Test
	void summaryIsTrimmedTo300() {
		assertEquals("A tiny oak hut.", Messages.summary("  A tiny oak hut. \n"));
		String cut = Messages.summary("🏰".repeat(400));
		assertEquals(300, cut.codePointCount(0, cut.length()));
		assertEquals("🏰".repeat(299) + "…", cut);
		assertEquals("x".repeat(300), Messages.summary("x".repeat(300)));
	}

	@Test
	void claudeCodeProblemsUseTheSpecsWords() {
		assertEquals("AI Builder needs Claude Code: install it, or set claude_path in config/aibuilder.json.", Messages.notInstalled());
		assertEquals("AI Builder runs on your Claude Code login: open a terminal, run claude and log in.", Messages.notLoggedIn());
		assertEquals("AI Builder runs on a Claude subscription (Pro or Max), but Claude Code is logged in with api_key. Run claude and log in"
				+ " with your subscription.", Messages.notSubscription("api_key"));
	}

	@Test
	void tooLongSaysHowLong() {
		assertEquals("That prompt is too long: 2,345 characters, the limit is 2,000.",
				Messages.problem(Problem.TOO_LONG, "a".repeat(2345)));
	}

	@Test
	void briefLineNamesTheBuild() {
		Map<String, String> palette = new LinkedHashMap<>();
		palette.put("primary", "minecraft:stone_bricks");
		palette.put("roof", "minecraft:dark_oak_stairs");
		palette.put("glass", "minecraft:glass");
		assertEquals("Designing: castle (gothic, 48×44, stone bricks + dark oak stairs)…",
				Messages.designing(new Brief("castle", "gothic", 48, 40, 44, palette, List.of())));
	}

	@Test
	void briefLineLeavesOutWhatTheBriefDoesntSay() {
		assertEquals("Designing: hut…", Messages.designing(new Brief("hut", "", 0, 0, 0, Map.of(), List.of())));
	}

	@Test
	void doneLineMentionsSkippedBlocksOnlyWhenThereAreSome() {
		assertEquals("Done: 6,214 blocks placed.", Messages.done(6214, 0));
		assertEquals("Done: 10 blocks placed. Skipped 1 block where a player stood.", Messages.done(10, 1));
	}

	@Test
	void undoLineMentionsKeptBlocksOnlyWhenThereAreSome() {
		assertEquals("Undone: 120 blocks restored.", Messages.undone(120, 0));
		assertEquals("Undone: 1,234 blocks restored. Kept 2 blocks you changed.", Messages.undone(1234, 2));
		assertEquals("Undone: 1 block restored. Kept 1 block you changed.", Messages.undone(1, 1));
	}

	@Test
	void statusCountsBlocksAndToolCalls() {
		assertEquals("Building walls (2/5) · 1,234 blocks placed · 12 of 150 tool calls.", Messages.status("walls (2/5)", 1234, 12, 150));
		assertEquals("Building · 1 block placed · 1 of 150 tool calls.", Messages.status("", 1, 1, 150));
	}
}
