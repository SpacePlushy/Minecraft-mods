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

	/** Why a player can't build here, and what would change it (spec §15). */
	@Test
	void refusalsSayWhy() {
		assertTrue(Messages.notListed().contains("allowed_players") && Messages.notListed().contains("config/aibuilder.json"), Messages.notListed());
		assertTrue(Messages.needsOp(3).contains("operator level 3"), Messages.needsOp(3));
		assertEquals("That's 20 builds in the last hour, as many as AI Builder starts; the next can start in 40 minutes.",
				Messages.rateLimited(20, 40 * 60_000));
		assertTrue(Messages.rateLimited(20, 61_000).endsWith("in 2 minutes."), "minutes round up");
		assertTrue(Messages.rateLimited(1, 30_000).endsWith("in 1 minute."), Messages.rateLimited(1, 30_000));
	}

	/** The HUD panel's counts, under its phase list (spec §5.3). */
	@Test
	void hudCountsBlocksAndTheBudget() {
		assertEquals("12,345 blocks placed", Messages.hudPlaced(12_345));
		assertEquals("1 block placed", Messages.hudPlaced(1));
		assertEquals("Tool calls: 37 of 150", Messages.hudBudget(37, 150));
	}

	/** While a build runs the screen says what's under way (spec §5.1; PR review). */
	@Test
	void theScreenSaysWhatsUnderWay() {
		assertEquals("Building Test hut: walls · 26 blocks placed", Messages.screenStatus("Test hut", "walls", 26));
		assertEquals("Building · 1,234 blocks placed", Messages.screenStatus("", "", 1234));
	}

	/** A key pasted into a prompt by mistake never goes back out in the echo (chat, and the client's log). */
	@Test
	void aKeyInThePromptIsntEchoed() {
		String prompt = "a castle sk-ant-api03-abcdefghijklmnop";
		for (String line : List.of(Messages.accepted(prompt, SizeTier.AUTO), Messages.refining("Stonehold", prompt))) {
			assertTrue(!line.contains("abcdefgh") && line.contains("a castle [redacted]"), line);
		}
	}

	/**
	 * What a player is told while something runs: an undo can't be stopped; a build's owner can stop it; anyone else is
	 * told whose it is, as their cancel would be (the minors' review).
	 */
	@Test
	void theBusyLineNamesTheBuildsOwnerToOthers() {
		assertEquals("A build is already running: /aibuild cancel stops it.", Messages.busy(false, true, "Frankie"));
		assertEquals("Frankie's build is running: only they can stop it.", Messages.busy(false, false, "Frankie"));
		assertEquals("Someone else's build is running: only they can stop it.", Messages.busy(false, false, ""));
		assertEquals("An undo is still running.", Messages.busy(true, false, "Frankie"));
	}
}
