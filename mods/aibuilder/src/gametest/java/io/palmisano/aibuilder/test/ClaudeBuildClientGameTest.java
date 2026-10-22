package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.GameTestSupport.check;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.palmisano.aibuilder.BuildManager;
import io.palmisano.aibuilder.TestHooks;
import io.palmisano.aibuilder.client.BuilderScreen;
import io.palmisano.aibuilder.client.PromptHistoryStore;
import io.palmisano.aibuilder.core.request.Messages;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.world.SessionStore;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

/**
 * A real build from a prompt in the real game, with the fake Claude Code in place of the real one: the launch, the
 * start check, the MCP server, the tools, placement, the end messages, undo, the usage limit and a logged-out login.
 */
@SuppressWarnings("UnstableApiUsage")
public class ClaudeBuildClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestSetup.live()) {
			return; // a live run is for LiveBuildClientGameTest alone
		}
		try (TestSingleplayerContext singleplayer = GameTestSupport.world(ctx)) {
			TestServerContext server = singleplayer.getServer();
			try {
				// The fake's build: get_context, submit_brief, a hollow 3×3×3 oak box, a checkpoint, finish.
				TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "build");
				GameTestSupport.runPlayerCommand(ctx, "aibuild a test hut");
				GameTestSupport.awaitChat(ctx, Messages.accepted("a test hut", SizeTier.AUTO));
				GameTestSupport.awaitChatStartingWith(ctx, "Designing: Test hut", 600);
				GameTestSupport.awaitChat(ctx, "A tiny oak hut.", 600);
				GameTestSupport.awaitChatStartingWith(ctx, "Done: ", 600);
				ctx.waitTicks(10);
				ctx.takeScreenshot("aibuilder-fake-claude-hut");
				GameTestSupport.runPlayerCommand(ctx, "aibuild undo");
				GameTestSupport.awaitChatStartingWith(ctx, "Undone: ", 600);

				// config/aibuilder.json (spec §14) applies to the next build without a restart: with one tool call, the
				// fake's fill is refused (the budget's spent) and it finishes in its grace calls with nothing placed,
				// where the default 150 build the 26-block hut.
				Path config = FabricLoader.getInstance().getConfigDir().resolve("aibuilder.json");
				check(Files.isRegularFile(config), "no config/aibuilder.json written on the first run");
				String defaults = read(config);
				write(config, "{\"max_tool_iterations\": 1}");
				try {
					TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "build");
					GameTestSupport.runPlayerCommand(ctx, "aibuild a capped hut");
					GameTestSupport.awaitChatStartingWith(ctx, "Done: 0 blocks placed.", 600);
				} finally {
					write(config, defaults);
				}

				// The plan's usage limit: the build stops at once and says when it resets.
				TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "usage_limit");
				GameTestSupport.runPlayerCommand(ctx, "aibuild a limited hut");
				GameTestSupport.awaitChatStartingWith(ctx, "Your Claude usage limit is reached (resets ", 600);

				// The login lapsing mid-build (the check passed, then the stream says otherwise): the spec's line word for word.
				TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "logged_out");
				GameTestSupport.runPlayerCommand(ctx, "aibuild a hut after a logout");
				GameTestSupport.awaitChat(ctx, Messages.notLoggedIn(), 600);

				// A click (a book's page, a sign, a tellraw link) can't send a prompt as if typed; the mod's own buttons
				// (undo, status) still go through (M4 review).
				TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "build");
				ctx.runOnClient(mc -> mc.player.connection.sendUnattendedCommand("aibuild a castle from a link", mc.gui.screen()));
				ctx.runOnClient(mc -> mc.player.connection.sendUnattendedCommand("aibuild status", mc.gui.screen()));
				GameTestSupport.awaitChat(ctx, Messages.nothingBuilding(), 200);
				ctx.waitTicks(20);
				check(!server.computeOnServer(s -> BuildManager.of(s).busy()), "a clicked prompt started a build");
				check(GameTestSupport.chat().stream().noneMatch(line -> line.startsWith("AI Builder: building \"a castle from a link\"")),
						"a clicked prompt was taken: " + GameTestSupport.chat());

				// Logged out: the check before the launch says so, and nothing starts.
				TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "build", "FAKE_CLAUDE_LOGGED_OUT", "1");
				GameTestSupport.runPlayerCommand(ctx, "aibuild a logged out hut");
				GameTestSupport.awaitChatCount(ctx, Messages.notLoggedIn(), 2, 600);
				ctx.waitTicks(5);
				check(!server.computeOnServer(s -> BuildManager.of(s).busy()), "a build started without a login");

				// Refinement (spec §5.4): a prompt next to the last build changes that build, in the same Claude Code
				// session, inside the box of what it built grown by 16 (here clipped by the world's floor, 3 below).
				UUID owner = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getUUID());
				TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "build");
				GameTestSupport.runPlayerCommand(ctx, "aibuild a hut to refine");
				server.waitFor(s -> SessionStore.get(s).get(owner).isPresent() && !BuildManager.of(s).busy());
				String session = server.computeOnServer(s -> SessionStore.get(s).get(owner).orElseThrow().claudeSession());
				// The screen says the next prompt changes the hut; its toggle makes it a new build instead.
				ctx.runOnClient(mc -> mc.gui.setScreen(new BuilderScreen(PromptHistoryStore.standard())));
				ctx.waitFor(mc -> mc.gui.screen() instanceof BuilderScreen screen && screen.modeLine().equals("Refining: Test hut"), 100);
				ctx.clickScreenButton("Refining: Test hut");
				String toggled = ctx.computeOnClient(mc -> ((BuilderScreen) mc.gui.screen()).modeLine());
				check(toggled.equals("New build here"), "the screen's toggle: " + toggled);
				ctx.runOnClient(mc -> mc.gui.setScreen(null));
				TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "refine");
				GameTestSupport.runPlayerCommand(ctx, "aibuild add a door");
				GameTestSupport.awaitChat(ctx, Messages.refining("Test hut", "add a door"), 200);
				GameTestSupport.awaitChat(ctx, "Resumed " + session + "; the allowed box is x -17..17, y -3..18, z -15..19", 600);
				server.waitFor(s -> !BuildManager.of(s).busy());
				aPromptWaitsForTheLastProcess(ctx, server, session);
				GameTestSupport.awaitChatCount(ctx, "Done: ", 5, 600);
				logsNeverHoldTheToken(ctx);
				offlineMidBuildEndsCleanly(ctx);
			} finally {
				TestHooks.claudeEnvironment = TestSetup.DEFAULT_SCENARIO;
			}
		}
	}

	/**
	 * One Claude Code process at a time, so a session is never resumed from two (M6 Review Focus; the fake refuses a
	 * session in use): right after a cancel, while the process that ran the hut's session takes its time to end, the
	 * next prompt, a refinement of the hut, is taken and starts once that process has ended.
	 */
	private static void aPromptWaitsForTheLastProcess(ClientGameTestContext ctx, TestServerContext server, String session) {
		TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "slow_exit");
		int designed = GameTestSupport.count("Designing: Test hut");
		int stopped = GameTestSupport.count("Stopped: ");
		GameTestSupport.runPlayerCommand(ctx, "aibuild add a slow porch");
		GameTestSupport.awaitChat(ctx, Messages.refining("Test hut", "add a slow porch"), 200);
		GameTestSupport.awaitChatCount(ctx, "Designing: Test hut", designed + 1, 600);
		GameTestSupport.runPlayerCommand(ctx, "aibuild cancel");
		GameTestSupport.awaitChatCount(ctx, "Stopped: ", stopped + 1, 200);
		check(server.computeOnServer(s -> BuildManager.of(s).lastProcessRunning()), "the slow process ended before the next prompt");
		TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "refine");
		GameTestSupport.runPlayerCommand(ctx, "aibuild add a window");
		GameTestSupport.awaitChat(ctx, Messages.refining("Test hut", "add a window"), 100);
		GameTestSupport.awaitChatCount(ctx, "Resumed " + session + "; the allowed box is x -17..17, y -3..18, z -15..19", 2, 600);
		check(GameTestSupport.chat().stream().noneMatch(line -> line.startsWith("Claude Code exited")), "two processes on one session: "
				+ GameTestSupport.chat());
	}

	/**
	 * Neither log holds the build's token or a key, even when Claude Code prints them (spec §16, M6 Review Focus): the
	 * fake shows its token in the stream, on stderr (the game log) and in its finish summary (chat), and a key-shaped
	 * string in the stream and the summary.
	 */
	private static void logsNeverHoldTheToken(ClientGameTestContext ctx) {
		Path game = FabricLoader.getInstance().getGameDir();
		Path tokenFile = game.resolve("aibuilder-test-token");
		try {
			Files.deleteIfExists(tokenFile);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "leak", "FAKE_CLAUDE_TOKEN_FILE", tokenFile.toString());
		int done = GameTestSupport.count("Done: ");
		GameTestSupport.runPlayerCommand(ctx, "aibuild new a leaky hut");
		GameTestSupport.awaitChatCount(ctx, "Done: ", done + 1, 600);
		String token = read(tokenFile).strip();
		check(token.length() >= 32, "the fake never wrote its token: " + token);
		String key = "sk-ant-api03-FAKE";

		// The build log is written off the server thread: its last line comes a moment after the chat line.
		Path logs = game.resolve("logs/aibuilder");
		ctx.waitFor(mc -> newest(logs, "_a-leaky-hut.jsonl") != null && read(newest(logs, "_a-leaky-hut.jsonl")).contains("\"type\":\"totals\""), 200);
		Path buildLog = newest(logs, "_a-leaky-hut.jsonl");
		Path streamLog = newest(logs, "_a-leaky-hut.stream.jsonl");
		check(streamLog != null, "no stream log next to " + buildLog);
		for (Path log : List.of(buildLog, streamLog, game.resolve("logs/latest.log"))) {
			String text = read(log);
			check(!text.contains(token), log.getFileName() + " holds the build's token");
			check(!text.contains(key), log.getFileName() + " holds a key");
		}
		check(read(streamLog).contains("Bearer [redacted]") && read(buildLog).contains("[redacted]"), "the leak never reached the logs to be scrubbed");
		check(GameTestSupport.chat().stream().noneMatch(line -> line.contains(token) || line.contains(key)), "chat holds the token or the key");

		// What the build log says: the request, Claude Code's start, both calls, the outcome and the totals, with the two
		// turns' usage among the calls (the stream and the calls come in on threads of their own).
		List<JsonObject> lines = read(buildLog).lines().map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
		List<String> types = lines.stream().map(line -> line.get("type").getAsString()).toList();
		check(types.stream().filter(type -> !type.equals("usage")).toList().equals(List.of("request", "claude", "tool", "tool", "outcome", "totals"))
				&& types.stream().filter(type -> type.equals("usage")).count() == 2, "the build log's lines: " + types);
		JsonObject request = lines.getFirst();
		check(request.get("prompt").getAsString().equals("a leaky hut") && request.get("mode").getAsString().equals("new"), "the request: " + request);
		check(lines.getLast().get("tool_calls").getAsInt() == 2 && lines.getLast().get("output_tokens").getAsLong() == 200, "the totals: " + lines.getLast());
	}

	/**
	 * The Wi-Fi goes off mid-build (spec §18's M6 check): the action bar shows Claude Code's retries, then chat says the
	 * connection went, with [Undo], and what went in before it went comes off again.
	 */
	private static void offlineMidBuildEndsCleanly(ClientGameTestContext ctx) {
		// The hut refined above still stands where this one goes: taken back first (after its refinements, too).
		int undone = GameTestSupport.count("Undone: ");
		GameTestSupport.runPlayerCommand(ctx, "aibuild undo");
		GameTestSupport.awaitChatCount(ctx, "Undone: ", undone + 1, 600);
		check(GameTestSupport.chat().getLast().equals(Messages.undone(26, 0)), "undoing the refined hut: " + GameTestSupport.chat().getLast());

		TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "offline");
		GameTestSupport.runPlayerCommand(ctx, "aibuild new an offline hut");
		GameTestSupport.awaitChatStartingWith(ctx, Messages.connectionLost("API Error: Connection error.") + " [Undo]", 600);
		check(GameTestSupport.overlay().stream().anyMatch(line -> line.startsWith("Can't reach Claude, retrying (attempt ")),
				"the action bar never showed the retries: " + GameTestSupport.overlay());
		GameTestSupport.runPlayerCommand(ctx, "aibuild undo");
		GameTestSupport.awaitChatCount(ctx, "Undone: ", undone + 2, 600);
		check(GameTestSupport.chat().getLast().equals(Messages.undone(26, 0)), "undoing the offline hut: " + GameTestSupport.chat().getLast());
	}

	/** The newest file in {@code dir} whose name ends with {@code suffix}, or null. */
	private static Path newest(Path dir, String suffix) {
		if (!Files.isDirectory(dir)) {
			return null;
		}
		try (Stream<Path> files = Files.list(dir)) {
			return files.filter(f -> f.getFileName().toString().endsWith(suffix)).max(Comparator.comparing(f -> f.getFileName().toString())).orElse(null);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String read(Path file) {
		try {
			return Files.readString(file);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void write(Path file, String text) {
		try {
			Files.writeString(file, text);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
