package io.palmisano.aibuilder.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.palmisano.aibuilder.core.agent.BuildEvents;
import io.palmisano.aibuilder.core.agent.BuildHandle;
import io.palmisano.aibuilder.core.agent.BuildOutcome;
import io.palmisano.aibuilder.core.agent.BuildRequest;
import io.palmisano.aibuilder.core.agent.ClaudeLaunch;
import io.palmisano.aibuilder.core.agent.ClaudeSettings;
import io.palmisano.aibuilder.core.agent.McpHandler;
import io.palmisano.aibuilder.core.agent.McpSessions;
import io.palmisano.aibuilder.core.agent.Scrub;
import io.palmisano.aibuilder.core.blocks.FakeCatalog;
import io.palmisano.aibuilder.core.blocks.Validator;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.request.Messages;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.tools.Budget;
import io.palmisano.aibuilder.core.tools.FakeWorld;
import io.palmisano.aibuilder.core.tools.RecordingSink;
import io.palmisano.aibuilder.core.tools.ToolCatalog;
import io.palmisano.aibuilder.core.tools.ToolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole Claude Code path offline (spec §17.2): a real subprocess (the fake Claude Code in
 * src/test/resources/fake-claude), real HTTP to the MCP server, the real tools over a recording sink.
 */
class ClaudeCodeBackendTest {
	private static final Path FAKE = Path.of("src/test/resources/fake-claude/FakeClaude.java").toAbsolutePath();
	private static final String JAVA = ProcessHandle.current().info().command().orElse("java");

	@TempDir
	Path temp;
	private final McpSessions sessions = new McpSessions();
	private final RecordingSink sink = new RecordingSink();
	private final FakeWorld world = new FakeWorld();
	private final List<String> notes = new CopyOnWriteArrayList<>();
	private final List<String> warnings = new CopyOnWriteArrayList<>();
	private final List<String> started = new CopyOnWriteArrayList<>();
	private final List<long[]> usage = new CopyOnWriteArrayList<>();
	private final Scrub scrub = new Scrub();
	/** Where the builds' folders go; the test's own folder unless a test says otherwise. */
	private Path root;
	private final UUID session = UUID.randomUUID();
	/** The request resumes {@link #session} (a refinement) instead of starting it. */
	private boolean resume;
	private McpHttpServer server;
	private Budget budget = new Budget(150);
	private Duration startupTimeout = Duration.ofSeconds(30);
	private volatile boolean listenerThrows;

	@BeforeEach
	void startServer() throws IOException {
		server = McpHttpServer.start(new McpHandler(ToolCatalog.load().tools(), sessions));
	}

	@AfterEach
	void stopAll() {
		ClaudeCodeBackend.stopAll();
		server.stop();
	}

	private BuildHandle start(String scenario, String prompt, Map<String, String> extra) {
		return start(scenario, prompt, extra, List.of(JAVA, FAKE.toString()));
	}

	private BuildHandle start(String scenario, String prompt, Map<String, String> extra, List<String> command) {
		Map<String, String> env = new HashMap<>(System.getenv());
		env.put("FAKE_CLAUDE_SCENARIO", scenario);
		env.putAll(extra);
		ClaudeCodeBackend backend = new ClaudeCodeBackend(new ClaudeCodeBackend.Config(command, ClaudeLaunch.environment(env),
				ClaudeSettings.DEFAULT, "You build.", new McpEndpoint(server.port(), sessions), root == null ? temp : root, temp.resolve("stream.jsonl"),
				startupTimeout,
				Duration.ofSeconds(5), Duration.ofSeconds(2), scrub));
		ToolExecutor tools = new ToolExecutor(new Validator(FakeCatalog.standard(), Validator.DEFAULT_BANNED), new LocalFrame(new Pos(0, 64, 0),
				Facing.SOUTH), Box.of(-12, -6, 0, 11, 23, 23), SizeTier.SMALL, 15_000, sink, budget, world);
		return backend.start(new BuildRequest(prompt, SizeTier.SMALL, session, resume), tools, new BuildEvents() {
			@Override
			public void status(String note) {
				notes.add(note);
				if (listenerThrows && note.equals(Messages.surveyingTerrain())) {
					throw new IllegalStateException("a listener that fails");
				}
			}

			@Override
			public void warn(String line) {
				warnings.add(line);
			}

			@Override
			public void started(String version, String model) {
				started.add(version + " " + model);
			}

			@Override
			public void usage(long input, long output, long cacheRead, long cacheWrite) {
				usage.add(new long[] {input, output, cacheRead, cacheWrite});
			}
		});
	}

	private BuildHandle start(String scenario) {
		return start(scenario, "a tiny hut", Map.of());
	}

	private static BuildOutcome outcome(BuildHandle handle) throws Exception {
		return handle.outcome().get(60, TimeUnit.SECONDS);
	}

	/** Waits until the fake has printed its start line: its tools are listed and the check has run. */
	private void awaitInit() throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < deadline) {
			Path log = temp.resolve("stream.jsonl");
			if (Files.exists(log) && Files.readString(log).contains("\"subtype\":\"init\"")) {
				return;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("the fake never started");
	}

	private List<Path> buildDirs() throws IOException {
		try (Stream<Path> files = Files.list(temp)) {
			return files.filter(p -> p.getFileName().toString().startsWith("build-")).toList();
		}
	}

	@Test
	void aScriptedBuildReachesTheTools() throws Exception {
		BuildHandle handle = start("build");
		assertEquals(BuildOutcome.FINISHED, outcome(handle));
		assertEquals("Test hut", sink.brief.title());
		assertEquals(1, sink.batches.size());
		assertEquals(List.of("walls"), sink.checkpoints);
		assertEquals("A tiny oak hut.", sink.finishedWith);
		assertTrue(notes.contains("Starting Claude…") && notes.contains("Surveying terrain…"), notes.toString());
		assertEquals(List.of(), buildDirs(), "the build's folder (and its token) outlived the build");
		assertEquals(0, sessions.size(), "the build's token wasn't revoked");
		assertTrue(Files.readString(temp.resolve("stream.jsonl")).contains("\"type\":\"result\""), "no stream log");
	}

	@Test
	void startAndUsageReachTheEvents() throws Exception {
		assertEquals(BuildOutcome.FINISHED, outcome(start("build")));
		assertEquals(List.of("0.0.0-fake claude-opus-5-5"), started);
		// The fake's five turns: get_context, submit_brief, fill, checkpoint, finish.
		assertEquals(5, usage.size());
		assertTrue(usage.stream().allMatch(u -> u[0] == 3 && u[1] == 100 && u[2] == 1000 && u[3] == 50), usage.toString());
		assertEquals(500, budget.outputTokens());
	}

	@Test
	void theStreamLogNeverHoldsTheToken() throws Exception {
		Path tokenFile = temp.resolve("token");
		assertEquals(BuildOutcome.FINISHED, outcome(start("leak", "a hut", Map.of("FAKE_CLAUDE_TOKEN_FILE", tokenFile.toString()))));
		String token = Files.readString(tokenFile).strip();
		assertTrue(token.length() >= 32, token);
		String stream = Files.readString(temp.resolve("stream.jsonl"));
		assertTrue(!stream.contains(token) && !stream.contains("sk-ant-api03-FAKE"), stream);
		assertTrue(stream.contains("Bearer [redacted]"), stream);
		// The build log scrubs with the same scrubber, so it has to know the token.
		assertEquals("token [redacted]", scrub.apply("token " + token));
	}

	/** A crashed game leaves its build's folder (its token long revoked): the next build takes away what's a day old. */
	@Test
	void staleBuildFoldersGo() throws Exception {
		Path stale = Files.createDirectories(temp.resolve("build-12345"));
		Files.writeString(stale.resolve("mcp.json"), "{}");
		Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
		// Another game's build, still going: left alone.
		Path fresh = Files.createDirectories(temp.resolve("build-67890"));
		assertEquals(BuildOutcome.FINISHED, outcome(start("build")));
		assertFalse(Files.exists(stale), "a day-old build folder stayed");
		assertTrue(Files.exists(fresh), "a fresh build folder was swept");
	}

	/** The token and the system prompt are written there: only the player may read or change anything in it. */
	@Test
	void theTempRootIsOwnerOnly() throws Exception {
		assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
		root = Files.createDirectories(temp.resolve("aibuilder"));
		Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwxrwx"));
		assertEquals(BuildOutcome.FINISHED, outcome(start("build")));
		assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root));
	}

	/** A root that's a link (someone else's folder, on a shared machine) isn't written through: a private one is used. */
	@Test
	void aLinkedTempRootIsNotUsed() throws Exception {
		Path elsewhere = Files.createDirectories(temp.resolve("elsewhere"));
		root = Files.createSymbolicLink(temp.resolve("aibuilder"), elsewhere);
		assertEquals(BuildOutcome.FINISHED, outcome(start("build")));
		try (Stream<Path> files = Files.list(elsewhere)) {
			assertEquals(List.of(), files.toList(), "the build wrote through the link");
		}
	}

	/**
	 * The Wi-Fi goes off mid-build (M6 check): Claude Code retries, then gives up with an API error and no HTTP status.
	 * The player sees the retries as they happen, then a line that says the connection went, not a bare "api_error".
	 */
	@Test
	void offlineMidBuildEndsCleanly() throws Exception {
		BuildHandle handle = start("offline");
		assertEquals(BuildOutcome.FAILED, outcome(handle));
		assertTrue(notes.contains(Messages.claudeUnreachable(1)) && notes.contains(Messages.claudeUnreachable(3)), notes.toString());
		assertEquals(Messages.connectionLost("API Error: Connection error."), handle.reason());
		// What it placed before the connection went is in the sink, for the undo.
		assertEquals(1, sink.batches.size());
	}

	/**
	 * Claude Code no longer has the session a refinement resumes (the world moved to another computer, a month went by):
	 * the build says so and is marked so, for the record to end (M6 review).
	 */
	@Test
	void aSessionThatCantBeResumedIsSaidSo() throws Exception {
		resume = true;
		BuildHandle handle = start("refine", "add a door", Map.of("FAKE_CLAUDE_SESSION_GONE", "1"));
		assertEquals(BuildOutcome.FAILED, outcome(handle));
		assertTrue(handle.sessionLost());
		assertEquals(Messages.sessionGone("No conversation found with session ID: " + session), handle.reason());
	}

	@Test
	void aNewSessionIsNeverLost() throws Exception {
		BuildHandle handle = start("crash", "a hut", Map.of());
		assertEquals(BuildOutcome.FAILED, outcome(handle));
		assertFalse(handle.sessionLost());
	}

	/** If the temp root can't be used, the private one in its place is the same every time: refinements resume there. */
	@Test
	void theFallbackRootIsTheSameEveryTime() throws Exception {
		Path elsewhere = Files.createDirectories(temp.resolve("elsewhere"));
		Path link = Files.createSymbolicLink(temp.resolve("aibuilder-link"), elsewhere);
		Path fallback = ClaudeCodeBackend.privateRoot(link);
		assertEquals(temp.resolve("aibuilder-link-" + System.getProperty("user.name")), fallback);
		assertEquals(fallback, ClaudeCodeBackend.privateRoot(link));
	}

	@Test
	void anExtraToolStopsTheBuildBeforeAnyCall() throws Exception {
		BuildHandle handle = start("extra_tool");
		assertEquals(BuildOutcome.FAILED, outcome(handle));
		assertTrue(handle.reason().contains("Bash"), handle.reason());
		assertEquals(0, budget.used(), "a tool call got through");
	}

	@Test
	void anApiKeyStopsTheBuild() throws Exception {
		BuildHandle handle = start("api_key");
		assertEquals(BuildOutcome.FAILED, outcome(handle));
		assertTrue(handle.reason().contains("ANTHROPIC_API_KEY"), handle.reason());
		assertEquals(0, budget.used());
	}

	@Test
	void aPluginStopsTheBuild() throws Exception {
		BuildHandle handle = start("plugin");
		assertEquals(BuildOutcome.FAILED, outcome(handle));
		assertTrue(handle.reason().contains("helper"), handle.reason());
	}

	@Test
	void usageLimitStopsAtOnce() throws Exception {
		long began = System.nanoTime();
		BuildHandle handle = start("usage_limit");
		assertEquals(BuildOutcome.LIMITED, outcome(handle));
		assertTrue(handle.reason().startsWith("Your Claude usage limit is reached (resets "), handle.reason());
		assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(20), "it waited for the fake's 30 s");
	}

	@Test
	void overageStopsAtOnce() throws Exception {
		BuildHandle handle = start("overage");
		assertEquals(BuildOutcome.LIMITED, outcome(handle));
		assertEquals(Messages.extraUsage(), handle.reason());
	}

	@Test
	void cancelEndsAHangingProcess() throws Exception {
		BuildHandle handle = start("hang");
		awaitInit();
		long began = System.nanoTime();
		handle.cancel();
		assertEquals(BuildOutcome.CANCELLED, outcome(handle));
		assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(4), "cancel took longer than its grace");
		assertEquals(0, ClaudeCodeBackend.running(), "the process outlived the cancel");
		assertEquals(0, budget.used());
	}

	@Test
	void theToolCallCapEndsTheProcess() throws Exception {
		budget = new Budget(5);
		BuildHandle handle = start("spam");
		assertEquals(BuildOutcome.CAPPED, outcome(handle));
		assertEquals("the 5 tool-call limit", handle.reason());
		assertTrue(budget.used() <= 5 + 1 + 3 + 1, "calls went on past the cap: " + budget.used());
	}

	@Test
	void aCrashIsAFailureWithItsStderr() throws Exception {
		BuildHandle handle = start("crash");
		assertEquals(BuildOutcome.FAILED, outcome(handle));
		assertTrue(handle.reason().contains("boom"), handle.reason());
	}

	@Test
	void loggedOutSaysToLogIn() throws Exception {
		BuildHandle handle = start("logged_out");
		assertEquals(BuildOutcome.FAILED, outcome(handle));
		assertEquals(Messages.notLoggedIn(), handle.reason());
	}

	@Test
	void noInitTimesOut() throws Exception {
		startupTimeout = Duration.ofSeconds(3);
		BuildHandle handle = start("no_init");
		assertEquals(BuildOutcome.FAILED, outcome(handle));
		assertTrue(handle.reason().contains("didn't start"), handle.reason());
	}

	/** Quotes, a newline, an emoji and a leading dash: the prompt goes on stdin, never on the command line. */
	@Test
	void thePromptArrivesVerbatim() throws Exception {
		String prompt = "a \"quoted\" castle\nwith a moat 🏰 --x";
		BuildHandle handle = start("echo", prompt, Map.of());
		assertEquals(BuildOutcome.FINISHED, outcome(handle));
		// Claude didn't call finish: its last text is passed on as the summary.
		assertEquals(prompt, sink.finishedWith);
	}

	/**
	 * Claude Code started through a wrapper that doesn't exec (a shell script, Windows' npm claude.cmd): a cancel must
	 * end the wrapper's children too, or Claude goes on spending the owner's usage with nobody watching (M4 review).
	 */
	@Test
	void cancelEndsAClaudeBehindAWrapper() throws Exception {
		List<String> wrapper = List.of("/bin/sh", "-c", "'" + JAVA + "' '" + FAKE + "' \"$@\"; exit $?", "sh");
		BuildHandle handle = start("hang", "a tiny hut", Map.of(), wrapper);
		awaitInit();
		// The wrapper and the fake under it, by pid (macOS doesn't show other processes' command lines).
		List<ProcessHandle> launched = ProcessHandle.current().descendants().toList();
		assertTrue(launched.size() >= 2, "expected the wrapper and the fake: " + launched);
		long began = System.nanoTime();
		handle.cancel();
		assertEquals(BuildOutcome.CANCELLED, handle.outcome().get(20, TimeUnit.SECONDS));
		assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(8), "cancel waited for the wrapped Claude Code");
		Thread.sleep(300);
		assertEquals(List.of(), launched.stream().filter(ProcessHandle::isAlive).toList(), "a wrapped Claude Code outlived the cancel");
	}

	/** Spec §12.2: "You've hit your session limit" is the usage limit, with its message, not a failure. */
	@Test
	void aSessionLimitIsTheUsageLimit() throws Exception {
		BuildHandle handle = start("session_limit");
		assertEquals(BuildOutcome.LIMITED, outcome(handle));
		assertTrue(handle.reason().startsWith("Your Claude usage limit is reached"), handle.reason());
	}

	/** Whatever goes wrong handling one line, the reader keeps reading (a stuck reader means a runaway Claude). */
	@Test
	void aThrowingListenerDoesntStopTheReader() throws Exception {
		listenerThrows = true;
		BuildHandle handle = start("build");
		assertEquals(BuildOutcome.FINISHED, outcome(handle));
		assertEquals("A tiny oak hut.", sink.finishedWith);
	}

	/** A stream log that can't be written (here a folder) never stops the build. */
	@Test
	void anUnwritableStreamLogDoesntStopTheBuild() throws Exception {
		Files.createDirectories(temp.resolve("stream.jsonl"));
		BuildHandle handle = start("build");
		assertEquals(BuildOutcome.FINISHED, outcome(handle));
	}

	@Test
	void stopAllEndsEveryProcess() throws Exception {
		BuildHandle handle = start("hang");
		awaitInit();
		ClaudeCodeBackend.stopAll();
		assertEquals(BuildOutcome.CANCELLED, outcome(handle));
		assertEquals(0, ClaudeCodeBackend.running());
	}
}
