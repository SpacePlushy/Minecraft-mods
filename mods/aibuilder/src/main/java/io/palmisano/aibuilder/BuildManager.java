package io.palmisano.aibuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.palmisano.aibuilder.backend.ClaudeChecks;
import io.palmisano.aibuilder.backend.ClaudeCodeBackend;
import io.palmisano.aibuilder.backend.McpEndpoint;
import io.palmisano.aibuilder.backend.McpHttpServer;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.survey.SurveyEncoder;
import io.palmisano.aibuilder.core.agent.AiBackend;
import io.palmisano.aibuilder.core.agent.BuildEvents;
import io.palmisano.aibuilder.core.agent.BuildHandle;
import io.palmisano.aibuilder.core.agent.BuildOutcome;
import io.palmisano.aibuilder.core.agent.BuildRequest;
import io.palmisano.aibuilder.core.agent.ClaudeLaunch;
import io.palmisano.aibuilder.core.agent.ClaudeSettings;
import io.palmisano.aibuilder.core.agent.McpHandler;
import io.palmisano.aibuilder.core.agent.McpSessions;
import io.palmisano.aibuilder.core.agent.ScriptedBackend;
import io.palmisano.aibuilder.core.blocks.Validator;
import io.palmisano.aibuilder.core.config.AiBuilderConfig;
import io.palmisano.aibuilder.core.config.ConfigFile;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.core.request.BuildPrompt;
import io.palmisano.aibuilder.core.request.BuildStatus;
import io.palmisano.aibuilder.core.request.Messages;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.site.BuildBox;
import io.palmisano.aibuilder.core.tools.Brief;
import io.palmisano.aibuilder.core.tools.Budget;
import io.palmisano.aibuilder.core.tools.PhaseProgress;
import io.palmisano.aibuilder.core.tools.ToolCatalog;
import io.palmisano.aibuilder.core.tools.ToolExecutor;
import io.palmisano.aibuilder.net.BuildStatusPayload;
import io.palmisano.aibuilder.world.BuildTickets;
import io.palmisano.aibuilder.world.JobScheduler;
import io.palmisano.aibuilder.world.McBlockCatalog;
import io.palmisano.aibuilder.world.McBuildSink;
import io.palmisano.aibuilder.world.McWorldReader;
import io.palmisano.aibuilder.world.Occupancy;
import io.palmisano.aibuilder.world.PlacementJob;
import io.palmisano.aibuilder.world.Positions;
import io.palmisano.aibuilder.world.Sites;
import io.palmisano.aibuilder.world.StateFactory;
import io.palmisano.aibuilder.world.UndoRunner;
import io.palmisano.aibuilder.world.UndoStore;
import io.palmisano.aibuilder.world.UndoStore.StoredBuild;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The server's builds, and the one way a prompt or a build command gets in. At most one job runs at a time, a
 * build or an undo, and only for the world's owner (spec §15). One manager per server: made when it starts, and
 * when it stops, a running build is stopped with what it placed saved for undo. Server thread only.
 */
public final class BuildManager {
	public enum Outcome {
		STARTED,
		REFUSED,
		BUSY,
		NOTHING,
		DONE
	}

	private static BuildManager current;
	private static final McpSessions SESSIONS = new McpSessions();
	private static McpHttpServer mcp;
	private static final ExecutorService CHECKS = Executors.newCachedThreadPool(task -> {
		Thread t = new Thread(task, "aibuilder-checks");
		t.setDaemon(true);
		return t;
	});

	private final MinecraftServer server;
	private Build build;
	private Undo undo;
	private Starting starting;
	private int ticksToStatus;
	private AiBuilderConfig config;
	private FileTime configTime;

	private BuildManager(MinecraftServer server) {
		this.server = server;
	}

	/**
	 * {@code config/aibuilder.json} (spec §14), read again whenever it has changed, so an edit applies to the next
	 * build without a restart. Written with the defaults on the first run. Server thread.
	 */
	public AiBuilderConfig config() {
		Path dir = FabricLoader.getInstance().getConfigDir();
		FileTime time = modified(dir.resolve(ConfigFile.NAME));
		if (config == null || time == null || !time.equals(configTime)) {
			config = ConfigFile.load(dir, AiBuilderMod.LOG::warn);
			configTime = modified(dir.resolve(ConfigFile.NAME));
		}
		return config;
	}

	private static FileTime modified(Path file) {
		try {
			return Files.getLastModifiedTime(file);
		} catch (IOException e) {
			return null;
		}
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> current = new BuildManager(server));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (current != null && current.server == server) {
				current.stop();
				current = null;
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (current != null && current.server == server) {
				current.tick();
			}
		});
	}

	public static BuildManager of(MinecraftServer server) {
		if (current == null || current.server != server) {
			current = new BuildManager(server);
		}
		return current;
	}

	/** A build or an undo is running, or Claude Code is being checked for one. */
	public boolean busy() {
		return build != null || undo != null || starting != null;
	}

	/**
	 * A prompt from {@code /aibuild <prompt>} or the screen: a real build. The site is taken where the player stands
	 * now; Claude Code is found and its login checked on a worker (a process, about a second); then the build starts
	 * with the Claude Code backend, exactly as {@code /aibuild test} does with its script.
	 */
	public void submit(ServerPlayer player, String rawPrompt, SizeTier size) {
		if (!BuildPermission.canBuild(player)) {
			reply(player, Messages.ownerOnly());
			return;
		}
		String prompt = BuildPrompt.normalize(rawPrompt);
		BuildPrompt.Problem problem = BuildPrompt.check(prompt);
		if (problem != BuildPrompt.Problem.NONE) {
			reply(player, Messages.problem(problem, prompt));
			return;
		}
		if (busy()) {
			reply(player, undo != null ? Messages.undoing() : Messages.busy());
			return;
		}
		AiBuilderConfig cfg = config();
		LocalFrame frame = Sites.ahead(player, cfg.siteGap());
		ServerLevel level = player.level();
		Optional<Box> allowed = BuildBox.allowed(size, frame.origin().y(), level.getMinY(), level.getMaxY());
		if (allowed.isEmpty()) {
			reply(player, Messages.noRoom());
			return;
		}
		if (cfg.backend().equals("scripted")) {
			// Offline (spec §14): every prompt builds the demo house through the real tools, no Claude.
			reply(player, Messages.accepted(prompt, size));
			build = new Build(player, level, frame, allowed.get(), demo(), new BuildRequest(prompt, size), cfg.placementRate(), budget(cfg), cfg);
			return;
		}
		Starting start = new Starting(player.getUUID());
		starting = start;
		reply(player, Messages.accepted(prompt, size));
		Map<String, String> environment = childEnvironment();
		List<String> override = TestHooks.claudeCommand;
		CompletableFuture.supplyAsync(() -> check(override, environment, cfg.claudePath()), CHECKS).whenComplete((checked, error) -> server.execute(() -> {
			if (starting != start) {
				return;
			}
			starting = null;
			if (start.cancelled) {
				return;
			}
			if (error != null || !checked.problem().isEmpty()) {
				tell(start.owner, Component.literal(error != null ? Messages.failed(reason(error)) : checked.problem()));
				return;
			}
			ServerPlayer owner = server.getPlayerList().getPlayer(start.owner);
			if (owner == null) {
				return;
			}
			try {
				build = new Build(owner, level, frame, allowed.get(), claudeBackend(checked.command(), environment, owner, cfg),
						new BuildRequest(prompt, size), cfg.placementRate(), budget(cfg), cfg);
			} catch (IOException e) {
				tell(start.owner, Component.literal(Messages.failed("the mod's tool server couldn't start: " + e.getMessage())));
			}
		}));
	}

	/** A build's caps from the config (spec §12.2): tool calls, output tokens, minutes. */
	private static Budget budget(AiBuilderConfig cfg) {
		return new Budget(cfg.maxToolIterations(), cfg.maxTokensPerBuild(), cfg.maxBuildMinutes() * 60_000L, System::currentTimeMillis);
	}

	private static ScriptedBackend demo() {
		return new ScriptedBackend(resource("/aibuilder/scripts/demo_house.json"), task -> Thread.ofPlatform().daemon().name("aibuilder-build").start(task));
	}

	/**
	 * The command to launch, or the player's line for why there's none (not installed, not logged in…); {@code path}
	 * is the config's claude_path ("" to search). Blocking.
	 */
	private static Checked check(List<String> override, Map<String, String> environment, String path) {
		List<String> command = override;
		if (command == null) {
			Optional<List<String>> found = ClaudeChecks.command(path.isEmpty() ? null : path, System.getenv(), Path.of(System.getProperty("user.home")),
					Files::isExecutable);
			if (found.isEmpty()) {
				return new Checked(List.of(), Messages.notInstalled());
			}
			command = found.get();
		}
		return new Checked(command, ClaudeChecks.login(command, environment).orElse(""));
	}

	private record Checked(List<String> command, String problem) {
	}

	/** A prompt waiting for its Claude Code check; a cancel meanwhile drops it. Server thread. */
	private static final class Starting {
		final UUID owner;
		boolean cancelled;

		Starting(UUID owner) {
			this.owner = owner;
		}
	}

	/** The game's environment as the child gets it (spec §12.2), with any test scenario. */
	private static Map<String, String> childEnvironment() {
		Map<String, String> env = new HashMap<>(System.getenv());
		env.putAll(TestHooks.claudeEnvironment);
		return ClaudeLaunch.environment(env);
	}

	private ClaudeCodeBackend claudeBackend(List<String> command, Map<String, String> environment, ServerPlayer player, AiBuilderConfig cfg)
			throws IOException {
		String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
		Path streamLog = server.getServerDirectory().resolve("logs/aibuilder").resolve(time + "_" + player.getName().getString() + ".stream.jsonl");
		// The turn backstop stays above the tool-call cap, whatever the config sets it to.
		ClaudeSettings settings = new ClaudeSettings(cfg.model(), cfg.effort(), Math.max(Tunables.MAX_TURNS, cfg.maxToolIterations() + 50));
		return new ClaudeCodeBackend(new ClaudeCodeBackend.Config(command, environment, settings, systemPrompt(), endpoint(),
				Path.of(System.getProperty("java.io.tmpdir"), "aibuilder"), streamLog, Duration.ofSeconds(Tunables.STARTUP_TIMEOUT_SECONDS),
				Duration.ofSeconds(Tunables.CAP_GRACE_SECONDS), Duration.ofSeconds(Tunables.CANCEL_GRACE_SECONDS)));
	}

	/** {@code config/aibuilder/builder_system.md} if the player made one (spec §13), else the mod's own. */
	private static String systemPrompt() {
		Path custom = FabricLoader.getInstance().getConfigDir().resolve("aibuilder/builder_system.md");
		if (Files.isRegularFile(custom)) {
			try {
				return Files.readString(custom, StandardCharsets.UTF_8);
			} catch (IOException e) {
				AiBuilderMod.LOG.warn("Couldn't read {}; using the built-in system prompt", custom, e);
			}
		}
		return resource("/aibuilder/prompts/builder_system.md");
	}

	/** The MCP server, one per game, started on the first build and stopped with the server. */
	private static synchronized McpEndpoint endpoint() throws IOException {
		if (mcp == null) {
			mcp = McpHttpServer.start(new McpHandler(ToolCatalog.load().tools(), SESSIONS));
		}
		return new McpEndpoint(mcp.port(), SESSIONS);
	}

	private static synchronized void stopMcp() {
		if (mcp != null) {
			mcp.stop();
			mcp = null;
		}
	}

	/** {@code /aibuild test}: the demo house through the real tools, in front of the player, no Claude. */
	public Outcome startTest(ServerPlayer player) {
		if (!BuildPermission.canBuild(player)) {
			reply(player, Messages.ownerOnly());
			return Outcome.REFUSED;
		}
		if (busy()) {
			reply(player, undo != null ? Messages.undoing() : Messages.busy());
			return Outcome.BUSY;
		}
		AiBuilderConfig cfg = config();
		LocalFrame frame = Sites.ahead(player, cfg.siteGap());
		ServerLevel level = player.level();
		Optional<Box> allowed = BuildBox.allowed(SizeTier.AUTO, frame.origin().y(), level.getMinY(), level.getMaxY());
		if (allowed.isEmpty()) {
			reply(player, Messages.noRoom());
			return Outcome.NOTHING;
		}
		// Slow on purpose: something to watch, check on with /aibuild status and cancel half-way. Its own caps, so
		// the demo works whatever the config says.
		build = new Build(player, level, frame, allowed.get(), demo(), new BuildRequest("the demo house", SizeTier.AUTO),
				Tunables.DEMO_BLOCKS_PER_TICK, new Budget(Tunables.MAX_TOOL_CALLS), cfg);
		return Outcome.STARTED;
	}

	/** {@code /aibuild cancel}: stop at once; what's placed stays, finalized, and can be undone. */
	public Outcome cancel(ServerPlayer player) {
		if (!BuildPermission.canBuild(player)) {
			reply(player, Messages.ownerOnly());
			return Outcome.REFUSED;
		}
		if (starting != null) {
			// Still checking Claude Code: nothing has started, so nothing is left to undo.
			starting.cancelled = true;
			starting = null;
			reply(player, Messages.stoppedBeforeStart());
			return Outcome.DONE;
		}
		if (build == null) {
			reply(player, Messages.nothingToCancel());
			return Outcome.NOTHING;
		}
		build.cancel();
		return Outcome.DONE;
	}

	/** {@code /aibuild undo}: take back the player's newest build. */
	public Outcome undo(ServerPlayer player) {
		if (!BuildPermission.canBuild(player)) {
			reply(player, Messages.ownerOnly());
			return Outcome.REFUSED;
		}
		if (busy()) {
			reply(player, build != null ? Messages.busy() : Messages.undoing());
			return Outcome.BUSY;
		}
		// Left in the history until the undo has finished, so a crash half-way loses nothing.
		UndoStore store = UndoStore.get(server);
		Optional<StoredBuild> stored = store.peek(player.getUUID());
		if (stored.isEmpty()) {
			reply(player, Messages.nothingToUndo());
			return Outcome.NOTHING;
		}
		ServerLevel level = server.getLevel(stored.get().dimension());
		if (level == null) {
			store.remove(player.getUUID(), stored.get());
			reply(player, Messages.failed("that build's dimension doesn't exist any more"));
			return Outcome.NOTHING;
		}
		undo = new Undo(player.getUUID(), stored.get(), new UndoRunner(level, stored.get().journal(), config().placementRate()));
		return Outcome.STARTED;
	}

	/**
	 * {@code /aibuild survey}: reads the site in front of the player as a build would (Auto size) and shows what
	 * Claude would read: the summary in chat, the whole text in the log and in a file under the game folder.
	 */
	public Outcome survey(ServerPlayer player) {
		if (!BuildPermission.canBuild(player)) {
			reply(player, Messages.ownerOnly());
			return Outcome.REFUSED;
		}
		AiBuilderConfig cfg = config();
		LocalFrame frame = Sites.ahead(player, cfg.siteGap());
		ServerLevel level = player.level();
		Optional<Box> allowed = BuildBox.allowed(SizeTier.AUTO, frame.origin().y(), level.getMinY(), level.getMaxY());
		if (allowed.isEmpty()) {
			reply(player, Messages.noRoom());
			return Outcome.NOTHING;
		}
		UUID owner = player.getUUID();
		List<UndoJournal<BlockState, CompoundTag>> earlier = UndoStore.get(server).builds(owner).stream()
				.filter(build -> build.dimension().equals(level.dimension())).map(StoredBuild::journal).toList();
		McWorldReader reader = new McWorldReader(level, frame, allowed.get(), SizeTier.AUTO, player::blockPosition, earlier, new UndoJournal<>(),
				cfg);
		reply(player, Messages.surveying());
		reader.whenRead().whenComplete((survey, error) -> {
			if (error != null) {
				tell(owner, Component.literal(Messages.failed(reason(error))));
				return;
			}
			String text = SurveyEncoder.encode(survey.terrain(), allowed.get(), Tunables.SURVEY_MAX_CHARS);
			AiBuilderMod.LOG.info("Survey of the site in front of {}:\n{}", player.getName().getString(), text);
			String saved;
			try {
				Path folder = server.getServerDirectory().resolve("aibuilder/surveys");
				Files.createDirectories(folder);
				String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".txt";
				Files.writeString(folder.resolve(name), text, StandardCharsets.UTF_8);
				saved = "aibuilder/surveys/" + name;
			} catch (IOException e) {
				AiBuilderMod.LOG.warn("Couldn't save the survey", e);
				saved = "the log only (the file couldn't be written)";
			}
			// The header and the summaries; the maps are too long for chat.
			text.lines().takeWhile(line -> !line.startsWith("Surface blocks:")).forEach(line -> tell(owner, Component.literal(line)));
			tell(owner, Component.literal(Messages.surveySaved(text.length(), saved)));
		});
		return Outcome.STARTED;
	}

	/** {@code /aibuild status}. */
	public void status(ServerPlayer player) {
		if (build != null) {
			reply(player, Messages.status(build.sink.phase(), build.job.placed(), build.budget.used(), build.budget.max()));
		} else if (starting != null) {
			reply(player, Messages.startingClaude());
		} else {
			reply(player, undo != null ? Messages.undoing() : Messages.nothingBuilding());
		}
	}

	private void tick() {
		if (build == null) {
			return;
		}
		// The running build's journal is in the undo history: an autosave mid-build writes it with the blocks.
		build.store.setDirty();
		if (--ticksToStatus > 0) {
			return;
		}
		ticksToStatus = Tunables.STATUS_INTERVAL_TICKS;
		ServerPlayer player = server.getPlayerList().getPlayer(build.owner);
		if (player != null) {
			// Before the brief (and during a retry) the backend's note; then the phase and the count.
			String note = build.note;
			player.sendOverlayMessage(Component.literal(note.isEmpty() ? Messages.progress(build.sink.phase(), build.job.placed()) : note));
			build.hud(player);
		}
	}

	/**
	 * The server is stopping and won't tick again. A running build is already in the undo history, so what it placed
	 * can be undone after a restart; an unfinished undo's build is still there too. No Claude Code process outlives
	 * the server, and the tool server goes with it.
	 */
	private void stop() {
		if (build != null) {
			build.handle.cancel();
			build.job.cancel();
			build.store.setDirty();
			build = null;
		}
		starting = null;
		undo = null;
		ClaudeCodeBackend.stopAll();
		stopMcp();
	}

	private static String resource(String path) {
		try (InputStream in = BuildManager.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException(path + " is missing from the mod");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void reply(ServerPlayer player, String text) {
		player.sendSystemMessage(Component.literal(text));
	}

	/** To the player if they're still here. */
	private void tell(UUID player, Component message) {
		ServerPlayer online = server.getPlayerList().getPlayer(player);
		if (online != null) {
			online.sendSystemMessage(message);
		}
	}

	/** A line with a clickable [Undo] after it. */
	private static Component withUndo(String text) {
		MutableComponent undo = Component.literal("[Undo]")
				.withStyle(style -> style.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent.RunCommand("/aibuild undo")));
		return Component.literal(text + " ").append(undo);
	}

	private static String reason(Throwable error) {
		Throwable cause = error.getCause() != null ? error.getCause() : error;
		return cause.getMessage() == null ? cause.toString() : cause.getMessage();
	}

	/**
	 * One running build: its job, the tools the backend calls, and what to say when it ends. Its journal goes into the
	 * player's undo history as it starts (spec §2: undo survives quitting and crashes), so every save of the world
	 * writes the journal together with the blocks it describes.
	 */
	private final class Build {
		final UUID owner;
		final ServerLevel level;
		final UndoJournal<BlockState, CompoundTag> journal = new UndoJournal<>();
		final UndoStore store;
		final McWorldReader world;
		final Budget budget;
		final PlacementJob job;
		final McBuildSink sink;
		final Set<ChunkPos> chunks;
		final BuildHandle handle;
		StoredBuild stored;
		boolean cancelled;
		String failure;
		/** Set when the backend stopped at a cap or the usage limit, with its line. */
		BuildOutcome stoppedBy;
		String stopReason = "";
		/** finish's summary, from the backend's thread. */
		volatile String summary = "";
		/** The backend's note for the action bar ("Starting Claude…"); "" for the phase line. Any thread. */
		volatile String note = "";
		/** The brief's title, once there is one. Server thread. */
		String title = "";
		/** The owner's client has the HUD up. Server thread. */
		boolean hudShown;

		Build(ServerPlayer player, ServerLevel level, LocalFrame frame, Box allowed, AiBackend backend, BuildRequest request, int blocksPerTick,
				Budget budget, AiBuilderConfig cfg) {
			owner = player.getUUID();
			this.level = level;
			this.budget = budget;
			store = UndoStore.get(server);
			// The player's earlier builds here are theirs to change: the survey doesn't protect what those left.
			List<UndoJournal<BlockState, CompoundTag>> earlier = store.builds(owner).stream()
					.filter(build -> build.dimension().equals(level.dimension())).map(StoredBuild::journal).toList();
			world = new McWorldReader(level, frame, allowed, request.size(), () -> {
				ServerPlayer online = server.getPlayerList().getPlayer(owner);
				return online == null ? null : online.blockPosition();
			}, earlier, journal, cfg);
			stored = new StoredBuild(level.dimension(), "", journal);
			store.push(owner, stored, cfg.undoHistorySize());
			long cap = cfg.sizeLimit(request.size());
			job = new PlacementJob(level, StateFactory.rotation(frame.facing()), journal, Occupancy.players(level), blocksPerTick,
					Tunables.TICK_BUDGET_NANOS, cap);
			chunks = BuildTickets.covering(Positions.toBlockPos(frame.toWorld(allowed.min())), Positions.toBlockPos(frame.toWorld(allowed.max())));
			BuildTickets.hold(level, chunks);
			JobScheduler.add(job);
			job.whenEnded().whenComplete(this::ended);
			// The sink's callbacks come from the backend's thread.
			sink = new McBuildSink(job, brief -> server.execute(() -> briefed(brief)), finished -> {
				summary = finished == null ? "" : finished;
				job.finish();
			});
			ToolExecutor tools = new ToolExecutor(new Validator(McBlockCatalog.get(), Set.copyOf(cfg.bannedBlocks())), frame, allowed, request.size(),
					cap, sink, budget, world);
			handle = backend.start(request, tools, new BuildEvents() {
				@Override
				public void status(String text) {
					note = text;
				}

				@Override
				public void warn(String line) {
					server.execute(() -> tell(owner, Component.literal(line)));
				}
			});
			handle.outcome().thenAccept(outcome -> server.execute(() -> backendEnded(outcome)));
		}

		void cancel() {
			cancelled = true;
			handle.cancel();
			job.cancel();
		}

		/**
		 * The HUD panel (spec §5.3), while the owner is alive and in the build's world; taken down while they're
		 * dead or elsewhere, and back when they return. Server thread.
		 */
		void hud(ServerPlayer player) {
			if (!ServerPlayNetworking.canSend(player, BuildStatusPayload.TYPE)) {
				return;
			}
			if (!player.isAlive() || player.level() != level) {
				if (hudShown) {
					ServerPlayNetworking.send(player, new BuildStatusPayload(BuildStatus.CLEAR));
					hudShown = false;
				}
				return;
			}
			PhaseProgress progress = sink.progress();
			ServerPlayNetworking.send(player, new BuildStatusPayload(new BuildStatus(title.isEmpty() ? Messages.hudUntitled() : title,
					progress.phases(), progress.current(), job.placed(), budget.used(), budget.max(), note)));
			hudShown = true;
		}

		private void briefed(Brief brief) {
			title = brief.title();
			StoredBuild titled = new StoredBuild(stored.dimension(), brief.title(), journal);
			store.replace(owner, stored, titled);
			stored = titled;
			tell(owner, Component.literal(Messages.designing(brief)));
		}

		/** Claude (or the script) is done: whatever it left queued still goes in, then the job ends. */
		private void backendEnded(BuildOutcome outcome) {
			if (!job.ended()) {
				switch (outcome) {
					case FAILED -> failure = handle.reason();
					case CAPPED, LIMITED -> {
						stoppedBy = outcome;
						stopReason = handle.reason();
					}
					default -> {
					}
				}
			}
			job.finish();
		}

		/** Server thread, once the job has placed everything and finalized. */
		private void ended(Integer skipped, Throwable error) {
			// Whatever ended the job (a failure too), the backend has nothing left to build with: stop it.
			if (handle != null) {
				handle.cancel();
			}
			BuildTickets.release(level, chunks);
			// A build that changed nothing leaves the history, so undo never answers with nothing restored. Frames count.
			boolean undoable = !journal.isEmpty();
			if (undoable) {
				store.setDirty();
			} else {
				store.remove(owner, stored);
			}
			String line;
			if (error != null) {
				line = Messages.failed(reason(error));
			} else if (failure != null) {
				// The backend's reason is the player's whole line (spec §12.2's messages word for word).
				line = failure;
			} else if (stoppedBy == BuildOutcome.CAPPED) {
				line = Messages.capped(stopReason, job.placed());
			} else if (stoppedBy == BuildOutcome.LIMITED) {
				line = stopReason;
			} else if (cancelled) {
				line = Messages.stopped(job.placed());
			} else {
				line = Messages.done(job.placed(), skipped);
			}
			ServerPlayer online = server.getPlayerList().getPlayer(owner);
			if (online != null) {
				// The last "Building …" would otherwise linger for seconds after the build is over; the HUD too.
				online.sendOverlayMessage(Component.empty());
				if (ServerPlayNetworking.canSend(online, BuildStatusPayload.TYPE)) {
					ServerPlayNetworking.send(online, new BuildStatusPayload(BuildStatus.CLEAR));
				}
			}
			if (error == null && failure == null && stoppedBy == null && !cancelled && !summary.isBlank()) {
				// finish's summary goes to chat (spec §8), above the count and [Undo].
				tell(owner, Component.literal(Messages.summary(summary)));
			}
			tell(owner, undoable ? withUndo(line) : Component.literal(line));
			if (build == this) {
				build = null;
			}
		}
	}

	/** One running undo. */
	private final class Undo {
		final UUID owner;
		final StoredBuild stored;
		final UndoRunner runner;

		Undo(UUID owner, StoredBuild stored, UndoRunner runner) {
			this.owner = owner;
			this.stored = stored;
			this.runner = runner;
			JobScheduler.add(runner);
			runner.result().whenComplete((result, error) -> {
				if (error != null) {
					// The build stays in the history, so the player can try again.
					tell(owner, Component.literal(Messages.failed(reason(error))));
				} else {
					UndoStore.get(server).remove(owner, stored);
					tell(owner, Component.literal(Messages.undone(result.restored(), result.kept())));
				}
				if (undo == this) {
					undo = null;
				}
			});
		}
	}
}
