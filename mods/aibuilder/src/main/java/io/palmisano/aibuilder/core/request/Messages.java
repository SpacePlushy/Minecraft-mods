package io.palmisano.aibuilder.core.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.palmisano.aibuilder.core.agent.Scrub;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.tools.Brief;

/** The chat lines the player sees about a prompt and a build. */
public final class Messages {
	private Messages() {
	}

	/** The prompt was taken: what's coming, and how to stop it (spec §5.3: builds take minutes, not seconds). */
	public static String accepted(String prompt, SizeTier size) {
		return "AI Builder: building \"" + echo(prompt) + "\" (" + size.label() + "). Claude plans before it builds, so give it a few minutes;"
				+ " /aibuild cancel stops it.";
	}

	/** A prompt that refines the last build (spec §5.4); /aibuild new builds new instead. */
	public static String refining(String title, String prompt) {
		return "AI Builder: changing " + title + ": \"" + echo(prompt) + "\". Claude plans before it builds, so give it a few minutes;"
				+ " /aibuild cancel stops it, and /aibuild new builds something new instead.";
	}

	/** The prompt as chat (and the client's log) shows it: a key pasted into it by mistake never goes back out. */
	private static String echo(String prompt) {
		return new Scrub().apply(prompt);
	}

	/** Someone else's build: where several players may build, only its owner follows it and stops it. */
	public static String notYourBuild(String owner) {
		return (owner.isEmpty() ? "That build is someone else's" : "That's " + owner + "'s build") + ": only they can stop it or see how it's"
				+ " going.";
	}

	/** A refined build that never got a title from its brief. */
	public static String untitledBuild() {
		return "your last build";
	}

	/** A refinement's Claude Code session isn't on this computer (any more): the record ends, the next prompt builds new. */
	public static String sessionGone(String detail) {
		return "Claude Code couldn't pick up that build where it left off (" + detail + "), so the next prompt there starts a new build.";
	}

	/** A prompt waited for the last build's Claude Code process, and it never ended. */
	public static String stillStopping() {
		return "The last build's Claude Code is still stopping; try again in a few seconds.";
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

	public static String notListed() {
		return "AI Builder builds on the Claude login of whoever runs this server, so only the players in allowed_players"
				+ " (config/aibuilder.json) can build.";
	}

	public static String needsOp(int level) {
		return "Building with AI Builder needs operator level " + level + " on this server.";
	}

	/** {@code rate_limit_per_hour} reached; the wait rounds up to whole minutes. */
	public static String rateLimited(int perHour, long waitMillis) {
		long minutes = Math.max(1, (waitMillis + 59_999) / 60_000);
		return "That's " + perHour + " builds in the last hour, as many as AI Builder starts; the next can start in " + minutes
				+ (minutes == 1 ? " minute." : " minutes.");
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

	/** At one of the mod's caps; {@code limit} is the budget's own words ("the 150 tool-call limit"). */
	public static String capped(String limit, int placed) {
		return "Stopped at " + limit + ": " + blocks(placed) + " placed.";
	}

	/** The action bar while the build's machine waits for a player in a piston's way (spec v2 §5.5). */
	public static String stepAside(Pos piston) {
		return "Step out of the way of the piston at " + piston.x() + " " + piston.y() + " " + piston.z() + " so the machine can start";
	}

	/** Redstone of the build's that could have moved or powered something that isn't the build's, put back as it was. */
	public static String takenOut(List<Pos> where) {
		String at = String.join(", ", where.stream().limit(5).map(p -> p.x() + " " + p.y() + " " + p.z()).toList())
				+ (where.size() > 5 ? " and " + (where.size() - 5) + " more" : "");
		return "Took out " + where.size() + (where.size() == 1 ? " redstone piece" : " redstone pieces")
				+ " that could have moved or powered something that isn't the build's: at " + at + ".";
	}

	public static String machineNotStarted() {
		return "The machine wasn't started: someone stood in a piston's way. Flip one of its levers to start it.";
	}

	public static String stoppedBeforeStart() {
		return "Stopped before Claude started.";
	}

	public static String claudeNotAnswering() {
		return "Claude Code didn't answer when asked about its login; try again in a moment.";
	}

	public static String failed(String reason) {
		return "Stopped: something went wrong (" + reason + ").";
	}

	/** The action bar while a build runs. */
	public static String progress(String phase, int placed) {
		return building(phase) + " · " + blocks(placed);
	}

	/** The screen's line while a build runs (spec §5.1): what's building, the phase under way, the count. */
	public static String screenStatus(String title, String phase, int placed) {
		String what = title.isBlank() ? "Building" : "Building " + title;
		return (phase.isBlank() ? what : what + ": " + phase) + " · " + blocks(placed) + " placed";
	}

	/** The HUD panel's title before the brief names the build. */
	public static String hudUntitled() {
		return "AI Builder";
	}

	/** The HUD panel's block count. */
	public static String hudPlaced(int placed) {
		return blocks(placed) + " placed";
	}

	/** The HUD panel's line over its budget bar. */
	public static String hudBudget(int used, int max) {
		return "Tool calls: " + used + " of " + max;
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

	public static String clickedPrompt() {
		return "AI Builder builds only what you type: a click tried to send it a prompt, so nothing was sent.";
	}

	public static String typeItYourself() {
		return "AI Builder only takes commands its owner types in chat.";
	}

	public static String startingClaude() {
		return "Starting Claude…";
	}

	public static String surveyingTerrain() {
		return "Surveying terrain…";
	}

	public static String claudeRetrying(int attempt) {
		return "Claude is busy, retrying (attempt " + attempt + ")…";
	}

	/** A retry with no answer at all: the connection, not Claude. */
	public static String claudeUnreachable(int attempt) {
		return "Can't reach Claude, retrying (attempt " + attempt + ")…";
	}

	/** Claude Code gave up on a request nothing answered: the Wi-Fi went, say. */
	public static String connectionLost(String detail) {
		return "Lost the connection to Claude (" + detail + "). What's built so far stays; /aibuild undo reverts it.";
	}

	public static String initRejected(String problem) {
		return "Claude Code started with more than the build tools (" + problem + "), so the build was stopped.";
	}

	public static String claudeDidNotStart(long seconds) {
		return "Claude Code didn't start within " + seconds + " seconds.";
	}

	public static String couldNotStartClaude(String why) {
		return "Couldn't start Claude Code: " + why;
	}

	public static String claudeStopped(String why) {
		return "Claude Code stopped: " + why;
	}

	public static String claudeExited(int code, String lastLine) {
		return "Claude Code exited (code " + code + ")" + (lastLine.isBlank() ? "." : ": " + lastLine);
	}

	public static String notInstalled() {
		return "AI Builder needs Claude Code: install it, or set claude_path in config/aibuilder.json.";
	}

	/** {@code claude auth status} gave no answer: a damaged install, say, which logging in won't fix. */
	public static String claudeBroken(String why) {
		return "Claude Code couldn't run (" + why + "): run claude in a terminal to see what's wrong, or reinstall it.";
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

	/** Paid extra usage took over from the plan: AI Builder stops at once (Frankie: never). */
	public static String extraUsage() {
		return "Claude started using paid extra usage, so the build stopped at once: AI Builder never builds on it. What's built so far"
				+ " stays; /aibuild undo reverts it.";
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
