package io.palmisano.aibuilder.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
	private McpHttpServer server;
	private Budget budget = new Budget(150);
	private Duration startupTimeout = Duration.ofSeconds(30);

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
		Map<String, String> env = new HashMap<>(System.getenv());
		env.put("FAKE_CLAUDE_SCENARIO", scenario);
		env.putAll(extra);
		ClaudeCodeBackend backend = new ClaudeCodeBackend(new ClaudeCodeBackend.Config(List.of(JAVA, FAKE.toString()), ClaudeLaunch.environment(env),
				ClaudeSettings.DEFAULT, "You build.", new McpEndpoint(server.port(), sessions), temp, temp.resolve("stream.jsonl"), startupTimeout,
				Duration.ofSeconds(5), Duration.ofSeconds(2)));
		ToolExecutor tools = new ToolExecutor(new Validator(FakeCatalog.standard(), Validator.DEFAULT_BANNED), new LocalFrame(new Pos(0, 64, 0),
				Facing.SOUTH), Box.of(-12, -6, 0, 11, 23, 23), SizeTier.SMALL, 15_000, sink, budget, world);
		return backend.start(new BuildRequest(prompt, SizeTier.SMALL), tools, new BuildEvents() {
			@Override
			public void status(String note) {
				notes.add(note);
			}

			@Override
			public void warn(String line) {
				warnings.add(line);
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

	@Test
	void stopAllEndsEveryProcess() throws Exception {
		BuildHandle handle = start("hang");
		awaitInit();
		ClaudeCodeBackend.stopAll();
		assertEquals(BuildOutcome.CANCELLED, outcome(handle));
		assertEquals(0, ClaudeCodeBackend.running());
	}
}
