package io.palmisano.aibuilder.core.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.palmisano.aibuilder.core.tools.Brief;

/** The chat lines the player sees about a prompt and a build. */
public final class Messages {
	private Messages() {
	}

	public static String received(String prompt, SizeTier size) {
		return "AI Builder got: " + prompt + " (" + size.label() + ")";
	}

	public static String problem(BuildPrompt.Problem problem, String prompt) {
		return switch (problem) {
			case EMPTY -> "Type something for AI Builder to build.";
			case TOO_LONG -> String.format(Locale.ROOT, "That prompt is too long: %,d characters, the limit is %,d.",
					prompt.codePointCount(0, prompt.length()), BuildPrompt.MAX_CODE_POINTS);
			case NONE -> throw new IllegalArgumentException("a prompt without a problem has nothing to report");
		};
	}

	public static String ownerOnly() {
		return "Only this world's owner can use AI Builder.";
	}

	/** The brief's line (spec §5.3): title, then style, footprint and the first two palette blocks it gave. */
	public static String designing(Brief brief) {
		List<String> about = new ArrayList<>();
		if (!brief.style().isBlank()) {
			about.add(brief.style());
		}
		if (brief.width() > 0 && brief.depth() > 0) {
			about.add(brief.width() + "×" + brief.depth());
		}
		List<String> blocks = brief.palette().values().stream().limit(2).map(Messages::readable).toList();
		if (!blocks.isEmpty()) {
			about.add(String.join(" + ", blocks));
		}
		return "Designing: " + brief.title() + (about.isEmpty() ? "" : " (" + String.join(", ", about) + ")") + "…";
	}

	public static String done(int placed, int skipped) {
		String line = "Done: " + blocks(placed) + " placed.";
		return skipped == 0 ? line : line + " Skipped " + blocks(skipped) + " where a player stood.";
	}

	public static String undone(int restored, int kept) {
		String line = "Undone: " + blocks(restored) + " restored.";
		return kept == 0 ? line : line + " Kept " + blocks(kept) + " you changed.";
	}

	public static String stopped(int placed) {
		return "Stopped: " + blocks(placed) + " placed.";
	}

	public static String failed(String reason) {
		return "Stopped: something went wrong (" + reason + ").";
	}

	/** The action bar while a build runs. */
	public static String progress(String phase, int placed) {
		return building(phase) + " · " + blocks(placed);
	}

	/** {@code /aibuild status} while a build runs. */
	public static String status(String phase, int placed, int toolCalls, int maxToolCalls) {
		return building(phase) + " · " + blocks(placed) + " placed · " + toolCalls + " of " + maxToolCalls + " tool calls.";
	}

	public static String busy() {
		return "A build is already running: /aibuild cancel stops it.";
	}

	public static String undoing() {
		return "An undo is still running.";
	}

	public static String nothingToCancel() {
		return "Nothing to cancel.";
	}

	public static String nothingToUndo() {
		return "Nothing to undo.";
	}

	public static String nothingBuilding() {
		return "Nothing is building.";
	}

	public static String surveying() {
		return "Reading the site in front of you, as a build would…";
	}

	public static String surveySaved(int characters, String path) {
		return "Full survey (" + String.format(Locale.ROOT, "%,d", characters) + " characters, what Claude reads) saved to " + path + ".";
	}

	/** Claude's finish summary for chat: trimmed, at most 300 characters (cut with "…"). */
	public static String summary(String summary) {
		String text = summary.strip();
		if (text.codePointCount(0, text.length()) <= 300) {
			return text;
		}
		return text.substring(0, text.offsetByCodePoints(0, 299)) + "…";
	}

	public static String typeItYourself() {
		return "AI Builder only takes commands its owner types in chat.";
	}

	public static String notInstalled() {
		return "AI Builder needs Claude Code: install it, or set claude_path in config/aibuilder.json.";
	}

	public static String notLoggedIn() {
		return "AI Builder runs on your Claude Code login: open a terminal, run claude and log in.";
	}

	public static String notSubscription(String method) {
		return "AI Builder runs on a Claude subscription (Pro or Max), but Claude Code is logged in with " + method
				+ ". Run claude and log in with your subscription.";
	}

	/** Spec §12.2's usage-limit line; {@code resets} is "at 14:30" and the like, or "" when unknown. */
	public static String usageLimit(String resets) {
		return "Your Claude usage limit is reached" + (resets.isEmpty() ? "" : " (resets " + resets + ")")
				+ ". What's built so far stays; /aibuild undo reverts it.";
	}

	public static String usageWarning(String resets) {
		return "Claude usage is close to its limit" + (resets.isEmpty() ? "" : " (resets " + resets + ")") + ".";
	}

	/** Paid extra usage is about to take over from the plan: AI Builder never lets it (Frankie). */
	public static String extraUsage() {
		return "Claude's paid extra usage would start now, so the build stopped: AI Builder never uses it. What's built so far stays;"
				+ " /aibuild undo reverts it.";
	}

	public static String noRoom() {
		return "There's no room to build here: this spot is outside the world's build height.";
	}

	private static String building(String phase) {
		return phase.isEmpty() ? "Building" : "Building " + phase;
	}

	/** "minecraft:dark_oak_stairs" → "dark oak stairs". */
	private static String readable(String blockId) {
		return blockId.substring(blockId.indexOf(':') + 1).replace('_', ' ');
	}

	private static String blocks(int count) {
		return String.format(Locale.ROOT, "%,d", count) + (count == 1 ? " block" : " blocks");
	}
}
