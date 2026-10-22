package io.palmisano.aibuilder.backend;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.agent.AiBackend;
import io.palmisano.aibuilder.core.agent.BuildEvents;
import io.palmisano.aibuilder.core.agent.BuildHandle;
import io.palmisano.aibuilder.core.agent.BuildOutcome;
import io.palmisano.aibuilder.core.agent.BuildRequest;
import io.palmisano.aibuilder.core.agent.ClaudeLaunch;
import io.palmisano.aibuilder.core.agent.ClaudeSettings;
import io.palmisano.aibuilder.core.agent.InitCheck;
import io.palmisano.aibuilder.core.agent.McpSession;
import io.palmisano.aibuilder.core.agent.StreamEvent;
import io.palmisano.aibuilder.core.agent.StreamEvents;
import io.palmisano.aibuilder.core.agent.UsageLimits;
import io.palmisano.aibuilder.core.request.Messages;
import io.palmisano.aibuilder.core.tools.Budget;
import io.palmisano.aibuilder.core.tools.ToolExecutor;
import io.palmisano.aibuilder.core.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real backend (spec §12.2): one {@code claude -p} process per build, launched as {@link ClaudeLaunch} says,
 * reaching the tools over the mod's MCP server with a token of its own. A reader thread follows its stream: the start
 * check, status hints, output tokens, usage limits, the result. A cancel, a cap, a usage limit or a failed check ends
 * the process: the token is revoked at once, so no further call lands, then SIGINT, then destroy. Quitting the game
 * ends every process too, so nothing keeps running on the owner's usage with nobody watching.
 */
public final class ClaudeCodeBackend implements AiBackend {
	/**
	 * {@code command}: the binary (a test's fake: {@code java FakeClaude.java}); {@code environment}: already
	 * {@link ClaudeLaunch#environment}'s; {@code streamLog}: where the raw stream goes, or null.
	 */
	public record Config(List<String> command, Map<String, String> environment, ClaudeSettings settings, String systemPrompt, McpEndpoint endpoint,
			Path tempRoot, Path streamLog, Duration startupTimeout, Duration capGrace, Duration cancelGrace) {
	}

	private static final Logger LOG = LoggerFactory.getLogger("aibuilder");
	private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	private static final Set<Run> RUNNING = ConcurrentHashMap.newKeySet();
	private static final ScheduledExecutorService TIMERS = Executors.newSingleThreadScheduledExecutor(task -> {
		Thread t = new Thread(task, "aibuilder-claude-timer");
		t.setDaemon(true);
		return t;
	});

	static {
		Runtime.getRuntime().addShutdownHook(new Thread(ClaudeCodeBackend::stopAll, "aibuilder-claude-stop"));
	}

	private final Config config;

	public ClaudeCodeBackend(Config config) {
		this.config = config;
	}

	/** Ends every running Claude Code process at once (the server or the game is stopping). */
	public static void stopAll() {
		for (Run run : List.copyOf(RUNNING)) {
			run.kill();
		}
	}

	/** Processes still running, for tests. */
	static int running() {
		return RUNNING.size();
	}

	@Override
	public BuildHandle start(BuildRequest request, ToolExecutor tools, BuildEvents events) {
		Run run = new Run(request, tools, events);
		run.launch();
		return run;
	}

	private record Stop(BuildOutcome kind, String reason) {
	}

	/** One process: its handle for the build, and the MCP session its token reaches. */
	private final class Run implements BuildHandle, McpSession {
		private final BuildRequest request;
		private final ToolExecutor tools;
		private final Budget budget;
		private final BuildEvents events;
		private final CompletableFuture<BuildOutcome> outcome = new CompletableFuture<>();
		/** True once the start check passes; false if it fails or the run stops first. */
		private final CompletableFuture<Boolean> ready = new CompletableFuture<>();
		private final AtomicReference<Stop> stop = new AtomicReference<>();
		private final AtomicInteger callsPastBudget = new AtomicInteger();
		private final long started = System.nanoTime();
		private final StringBuilder stderrTail = new StringBuilder();
		private volatile String reason = "";
		private volatile String token;
		private volatile Path buildDir;
		private volatile Process process;
		private volatile Thread stderr;
		private volatile ScheduledFuture<?> watchdog;
		private volatile boolean initSeen;
		private volatile boolean briefSeen;
		private volatile boolean retrying;
		private volatile boolean warned;
		/** When the budget was first found used up (nanoTime); 0 before. */
		private volatile long exhaustedAt;
		private volatile StreamEvent.Result result;
		/** The note the action bar shows, kept to come back to after a retry. */
		private volatile String note = "";

		Run(BuildRequest request, ToolExecutor tools, BuildEvents events) {
			this.request = request;
			this.tools = tools;
			this.budget = tools.budget();
			this.events = events;
		}

		void launch() {
			try {
				Path work = config.tempRoot().resolve("work");
				Files.createDirectories(work);
				buildDir = Files.createTempDirectory(config.tempRoot(), "build-");
				token = config.endpoint().sessions().open(this);
				Path mcp = buildDir.resolve("mcp.json");
				writeOwnerOnly(mcp, ClaudeLaunch.mcpConfig(config.endpoint().port(), token));
				Path systemPrompt = buildDir.resolve("builder_system.md");
				Files.writeString(systemPrompt, config.systemPrompt(), StandardCharsets.UTF_8);
				ProcessBuilder builder = new ProcessBuilder(ClaudeLaunch.arguments(config.command(), mcp, systemPrompt, config.settings(),
						UUID.randomUUID()));
				builder.directory(work.toFile());
				builder.environment().clear();
				builder.environment().putAll(config.environment());
				process = builder.start();
			} catch (IOException | RuntimeException e) {
				stop.compareAndSet(null, new Stop(BuildOutcome.FAILED, Messages.couldNotStartClaude(String.valueOf(e.getMessage()))));
				finish(-1);
				return;
			}
			RUNNING.add(this);
			status(Messages.startingClaude());
			stderr = Thread.ofPlatform().daemon().name("aibuilder-claude-err").start(this::drainStderr);
			Thread.ofPlatform().daemon().name("aibuilder-claude-out").start(this::read);
			watchdog = TIMERS.scheduleAtFixedRate(this::watch, 1, 1, TimeUnit.SECONDS);
			// On stdin, never the command line: any text works, and list flags can't swallow it (spec §12.2).
			try (Writer in = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
				in.write(request.prompt());
			} catch (IOException e) {
				// The process ended at once; the reader says why.
			}
		}

		@Override
		public void cancel() {
			stop(BuildOutcome.CANCELLED, "");
		}

		@Override
		public CompletableFuture<BuildOutcome> outcome() {
			return outcome;
		}

		@Override
		public String reason() {
			return reason;
		}

		@Override
		public boolean awaitReady(long millis) {
			try {
				return ready.get(millis, TimeUnit.MILLISECONDS);
			} catch (TimeoutException | ExecutionException e) {
				return false;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		/** A tool call from Claude Code. Past the budget, a few more get through (finish, say), then the process ends. */
		@Override
		public ToolResult call(String name, JsonObject arguments) {
			if (stop.get() != null) {
				return new ToolResult("This build has stopped.", true);
			}
			boolean over = budget.exhausted();
			ToolResult answer = tools.call(name, arguments);
			if (over || budget.exhausted()) {
				if (exhaustedAt == 0) {
					exhaustedAt = System.nanoTime();
				}
				if (callsPastBudget.incrementAndGet() > Tunables.CAP_GRACE_CALLS) {
					stop(BuildOutcome.CAPPED, budget.limitReached());
				}
			}
			return answer;
		}

		/**
		 * Reads to the end, whatever one line does: a reader that stopped would leave Claude Code running unwatched (no
		 * token count, no usage stop). A log that can't be written is dropped, not the build.
		 */
		private void read() {
			Writer log = openLog();
			try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = out.readLine()) != null) {
					if (log != null) {
						try {
							log.write(line);
							log.write('\n');
							log.flush();
						} catch (IOException e) {
							LOG.warn("Couldn't write the build's stream log; going on without it", e);
							closeQuietly(log);
							log = null;
						}
					}
					try {
						StreamEvents.parse(line).ifPresent(this::onEvent);
					} catch (RuntimeException e) {
						LOG.warn("Couldn't handle a line from Claude Code; going on", e);
					}
				}
			} catch (IOException e) {
				LOG.warn("Reading Claude Code's output failed", e);
			} finally {
				closeQuietly(log);
			}
			int code;
			try {
				code = process.waitFor();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				code = -1;
			}
			finish(code);
		}

		private void onEvent(StreamEvent event) {
			switch (event) {
				case StreamEvent.Init init -> {
					initSeen = true;
					LOG.info("Claude Code {} is building with {}", init.version(), init.model());
					Optional<String> problem = InitCheck.problem(init);
					if (problem.isPresent()) {
						stop(BuildOutcome.FAILED, Messages.initRejected(problem.get()));
					} else {
						ready.complete(true);
					}
				}
				case StreamEvent.Assistant turn -> {
					if (turn.error().equals("authentication_failed")) {
						stop(BuildOutcome.FAILED, Messages.notLoggedIn());
						return;
					}
					// The plan's limit can come as an API error ("You've hit your session limit", spec §12.2). Only an error
					// counts: Claude's own words about a size limit mustn't stop anything.
					if (!turn.error().isEmpty()) {
						Optional<String> limit = turn.error().equals("rate_limit") ? Optional.of(UsageLimits.resetsFromText(turn.text()))
								: UsageLimits.limitFromText(turn.text());
						if (limit.isPresent()) {
							stop(BuildOutcome.LIMITED, Messages.usageLimit(limit.get()));
							return;
						}
					}
					if (retrying) {
						retrying = false;
						events.status(note);
					}
					for (String tool : turn.toolNames()) {
						if (tool.equals("mcp__" + InitCheck.SERVER + "__submit_brief")) {
							briefSeen = true;
							status("");
						} else if (!briefSeen && (tool.endsWith("__get_context") || tool.endsWith("__survey_area"))) {
							status(Messages.surveyingTerrain());
						}
					}
				}
				case StreamEvent.OutputTokens tokens -> budget.addOutputTokens(tokens.tokens());
				case StreamEvent.Retry retry -> {
					retrying = true;
					events.status(Messages.claudeRetrying(retry.attempt()));
				}
				case StreamEvent.RateLimit limit -> {
					UsageLimits.Decision decision = UsageLimits.decide(limit, ZoneId.systemDefault(), Instant.now());
					if (decision.kind() == UsageLimits.Kind.STOP) {
						stop(BuildOutcome.LIMITED, decision.message());
					} else if (decision.kind() == UsageLimits.Kind.WARN && !warned) {
						warned = true;
						events.warn(decision.message());
					}
				}
				case StreamEvent.Result done -> result = done;
				case StreamEvent.Other other -> {
				}
			}
		}

		private void status(String text) {
			note = text;
			events.status(text);
		}

		/** Every second: a start that never came, or a budget used up with Claude still going. */
		private void watch() {
			if (outcome.isDone() || stop.get() != null) {
				return;
			}
			long now = System.nanoTime();
			if (!initSeen && now - started > config.startupTimeout().toNanos()) {
				stop(BuildOutcome.FAILED, Messages.claudeDidNotStart(config.startupTimeout().toSeconds()));
				return;
			}
			if (budget.exhausted()) {
				if (exhaustedAt == 0) {
					exhaustedAt = now;
				} else if (now - exhaustedAt >= config.capGrace().toNanos()) {
					stop(BuildOutcome.CAPPED, budget.limitReached());
				}
			}
		}

		/**
		 * The first stop wins. No more tool calls land from here on, and the process is asked to end: SIGINT, then
		 * destroy, then destroy forcibly. The whole tree, taken now: Claude Code may run under a wrapper that doesn't
		 * exec (a shell script, Windows' npm claude.cmd), and once the wrapper is gone its children can't be found.
		 */
		private void stop(BuildOutcome kind, String why) {
			if (!stop.compareAndSet(null, new Stop(kind, why))) {
				return;
			}
			ready.complete(false);
			revoke();
			Process p = process;
			if (p == null || !p.isAlive()) {
				return;
			}
			List<ProcessHandle> tree = p.descendants().toList();
			interrupt(p, tree);
			long grace = config.cancelGrace().toMillis();
			TIMERS.schedule(() -> destroyTree(p, tree, false), grace, TimeUnit.MILLISECONDS);
			TIMERS.schedule(() -> destroyTree(p, tree, true), 2 * grace, TimeUnit.MILLISECONDS);
		}

		/** The game is stopping: no time for a grace period. Children first, so none is left without its wrapper. */
		void kill() {
			Process p = process;
			List<ProcessHandle> tree = p == null ? List.of() : p.descendants().toList();
			stop(BuildOutcome.CANCELLED, "");
			if (p != null) {
				destroyTree(p, tree, true);
			}
		}

		/** The process is gone: tidy up and say how it ended. */
		private void finish(int code) {
			ScheduledFuture<?> timer = watchdog;
			if (timer != null) {
				timer.cancel(false);
			}
			RUNNING.remove(this);
			ready.complete(false);
			revoke();
			deleteBuildDir();
			Thread err = stderr;
			if (err != null) {
				try {
					err.join(2000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			Stop stopped = stop.get();
			StreamEvent.Result last = result;
			BuildOutcome kind;
			if (stopped != null) {
				kind = stopped.kind();
				reason = stopped.reason();
			} else if (last != null && last.subtype().equals("success") && !last.isError()) {
				kind = BuildOutcome.FINISHED;
				if (!tools.finished() && !last.text().isBlank()) {
					// Claude ended without calling finish: its last words are the summary.
					JsonObject args = new JsonObject();
					args.addProperty("summary", last.text().strip());
					tools.call("finish", args);
				}
			} else if (last != null && UsageLimits.limitFromText(last.text()).isPresent()) {
				kind = BuildOutcome.LIMITED;
				reason = Messages.usageLimit(UsageLimits.limitFromText(last.text()).get());
			} else if (last != null) {
				kind = BuildOutcome.FAILED;
				reason = Messages.claudeStopped(last.text().isBlank() ? last.terminalReason() : last.text());
			} else {
				kind = BuildOutcome.FAILED;
				String tail;
				synchronized (stderrTail) {
					tail = stderrTail.toString().strip();
				}
				reason = Messages.claudeExited(code, tail.isEmpty() ? "" : tail.substring(tail.lastIndexOf('\n') + 1));
			}
			outcome.complete(kind);
		}

		private void drainStderr() {
			try (BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = err.readLine()) != null) {
					LOG.info("[claude] {}", line);
					synchronized (stderrTail) {
						stderrTail.append(line).append('\n');
						if (stderrTail.length() > 2048) {
							stderrTail.delete(0, stderrTail.length() - 2048);
						}
					}
				}
			} catch (IOException e) {
				// The process ended.
			}
		}

		private static void closeQuietly(Writer writer) {
			if (writer != null) {
				try {
					writer.close();
				} catch (IOException e) {
					// Nothing more to lose.
				}
			}
		}

		private void revoke() {
			String t = token;
			if (t != null) {
				config.endpoint().sessions().revoke(t);
			}
		}

		private Writer openLog() {
			Path log = config.streamLog();
			if (log == null) {
				return null;
			}
			try {
				if (log.getParent() != null) {
					Files.createDirectories(log.getParent());
				}
				return Files.newBufferedWriter(log, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException e) {
				LOG.warn("Couldn't open the build's stream log {}", log, e);
				return null;
			}
		}

		/** mcp.json holds the token: readable by the owner only, and gone when the build ends. */
		private void deleteBuildDir() {
			Path dir = buildDir;
			if (dir == null) {
				return;
			}
			try (Stream<Path> files = Files.walk(dir)) {
				files.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException e) {
						LOG.warn("Couldn't delete {}", path);
					}
				});
			} catch (IOException e) {
				LOG.warn("Couldn't delete the build's folder {}", dir);
			}
		}
	}

	private static void writeOwnerOnly(Path file, String text) throws IOException {
		if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
			Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		}
		Files.writeString(file, text, StandardCharsets.UTF_8);
	}

	/**
	 * SIGINT to the process and everything under it, so Claude Code ends its turn the way it would for a person (the
	 * M4 spike); Windows has no SIGINT.
	 */
	private static void interrupt(Process process, List<ProcessHandle> tree) {
		if (WINDOWS) {
			destroyTree(process, tree, false);
			return;
		}
		List<String> kill = new ArrayList<>(List.of("kill", "-INT", Long.toString(process.pid())));
		tree.forEach(child -> kill.add(Long.toString(child.pid())));
		try {
			new ProcessBuilder(kill).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		} catch (IOException e) {
			destroyTree(process, tree, false);
		}
	}

	/** Every process of the tree still alive, children first, whether or not the parent still is. */
	private static void destroyTree(Process process, List<ProcessHandle> tree, boolean force) {
		Set<ProcessHandle> all = new LinkedHashSet<>(tree);
		process.descendants().forEach(all::add);
		for (ProcessHandle child : all) {
			if (child.isAlive()) {
				if (force) {
					child.destroyForcibly();
				} else {
					child.destroy();
				}
			}
		}
		if (process.isAlive()) {
			if (force) {
				process.destroyForcibly();
			} else {
				process.destroy();
			}
		}
	}
}
