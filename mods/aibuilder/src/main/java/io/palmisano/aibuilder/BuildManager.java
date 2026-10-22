package io.palmisano.aibuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.survey.SurveyEncoder;
import io.palmisano.aibuilder.core.agent.AiBackend;
import io.palmisano.aibuilder.core.agent.BuildHandle;
import io.palmisano.aibuilder.core.agent.BuildOutcome;
import io.palmisano.aibuilder.core.agent.BuildRequest;
import io.palmisano.aibuilder.core.agent.ScriptedBackend;
import io.palmisano.aibuilder.core.blocks.Validator;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.core.request.BuildPrompt;
import io.palmisano.aibuilder.core.request.Messages;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.site.BuildBox;
import io.palmisano.aibuilder.core.tools.Brief;
import io.palmisano.aibuilder.core.tools.Budget;
import io.palmisano.aibuilder.core.tools.ToolExecutor;
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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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

	private final MinecraftServer server;
	private Build build;
	private Undo undo;
	private int ticksToStatus;

	private BuildManager(MinecraftServer server) {
		this.server = server;
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

	/** A build or an undo is running. */
	public boolean busy() {
		return build != null || undo != null;
	}

	/** A prompt from {@code /aibuild <prompt>} or the screen. M1 echoes it; M4 starts Claude here. */
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
		reply(player, Messages.received(prompt, size));
	}

	/** {@code /aibuild test}: the demo house through the real tools, in front of the player, no Claude. */
	public Outcome startTest(ServerPlayer player) {
		if (!BuildPermission.canBuild(player)) {
			reply(player, Messages.ownerOnly());
			return Outcome.REFUSED;
		}
		if (busy()) {
			reply(player, build != null ? Messages.busy() : Messages.undoing());
			return Outcome.BUSY;
		}
		LocalFrame frame = Sites.ahead(player);
		ServerLevel level = player.level();
		Optional<Box> allowed = BuildBox.allowed(SizeTier.AUTO, frame.origin().y(), level.getMinY(), level.getMaxY());
		if (allowed.isEmpty()) {
			reply(player, Messages.noRoom());
			return Outcome.NOTHING;
		}
		ScriptedBackend demo = new ScriptedBackend(demoScript(), task -> Thread.ofPlatform().daemon().name("aibuilder-build").start(task));
		// Slow on purpose: something to watch, check on with /aibuild status and cancel half-way.
		build = new Build(player, frame, allowed.get(), demo, new BuildRequest("the demo house", SizeTier.AUTO),
				Tunables.DEMO_BLOCKS_PER_TICK);
		return Outcome.STARTED;
	}

	/** {@code /aibuild cancel}: stop at once; what's placed stays, finalized, and can be undone. */
	public Outcome cancel(ServerPlayer player) {
		if (!BuildPermission.canBuild(player)) {
			reply(player, Messages.ownerOnly());
			return Outcome.REFUSED;
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
		undo = new Undo(player.getUUID(), stored.get(), new UndoRunner(level, stored.get().journal(), Tunables.BLOCKS_PER_TICK));
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
		LocalFrame frame = Sites.ahead(player);
		ServerLevel level = player.level();
		Optional<Box> allowed = BuildBox.allowed(SizeTier.AUTO, frame.origin().y(), level.getMinY(), level.getMaxY());
		if (allowed.isEmpty()) {
			reply(player, Messages.noRoom());
			return Outcome.NOTHING;
		}
		UUID owner = player.getUUID();
		List<UndoJournal<BlockState, CompoundTag>> earlier = UndoStore.get(server).builds(owner).stream()
				.filter(build -> build.dimension().equals(level.dimension())).map(StoredBuild::journal).toList();
		McWorldReader reader = new McWorldReader(level, frame, allowed.get(), SizeTier.AUTO, player::blockPosition, earlier);
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
			player.sendOverlayMessage(Component.literal(Messages.progress(build.sink.phase(), build.job.placed())));
		}
	}

	/**
	 * The server is stopping and won't tick again. A running build is already in the undo history, so what it placed
	 * can be undone after a restart; an unfinished undo's build is still there too.
	 */
	private void stop() {
		if (build != null) {
			build.handle.cancel();
			build.job.cancel();
			build.store.setDirty();
			build = null;
		}
		undo = null;
	}

	private static String demoScript() {
		try (InputStream in = BuildManager.class.getResourceAsStream("/aibuilder/scripts/demo_house.json")) {
			if (in == null) {
				throw new IllegalStateException("the demo script is missing from the mod");
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
		final Budget budget = new Budget(Tunables.MAX_TOOL_CALLS);
		final PlacementJob job;
		final McBuildSink sink;
		final Set<ChunkPos> chunks;
		final BuildHandle handle;
		StoredBuild stored;
		boolean cancelled;
		String failure;
		/** finish's summary, from the backend's thread. */
		volatile String summary = "";

		Build(ServerPlayer player, LocalFrame frame, Box allowed, AiBackend backend, BuildRequest request, int blocksPerTick) {
			owner = player.getUUID();
			level = player.level();
			store = UndoStore.get(server);
			// The player's earlier builds here are theirs to change: the survey doesn't protect what those left.
			List<UndoJournal<BlockState, CompoundTag>> earlier = store.builds(owner).stream()
					.filter(build -> build.dimension().equals(level.dimension())).map(StoredBuild::journal).toList();
			world = new McWorldReader(level, frame, allowed, request.size(), () -> {
				ServerPlayer online = server.getPlayerList().getPlayer(owner);
				return online == null ? null : online.blockPosition();
			}, earlier);
			stored = new StoredBuild(level.dimension(), "", journal);
			store.push(owner, stored);
			job = new PlacementJob(level, StateFactory.rotation(frame.facing()), journal, Occupancy.players(level), blocksPerTick,
					Tunables.TICK_BUDGET_NANOS, request.size().maxChangedBlocks());
			chunks = BuildTickets.covering(Positions.toBlockPos(frame.toWorld(allowed.min())), Positions.toBlockPos(frame.toWorld(allowed.max())));
			BuildTickets.hold(level, chunks);
			JobScheduler.add(job);
			job.whenEnded().whenComplete(this::ended);
			// The sink's callbacks come from the backend's thread.
			sink = new McBuildSink(job, brief -> server.execute(() -> briefed(brief)), finished -> {
				summary = finished == null ? "" : finished;
				job.finish();
			});
			ToolExecutor tools = new ToolExecutor(new Validator(McBlockCatalog.get(), Validator.DEFAULT_BANNED), frame, allowed, request.size(),
					request.size().maxChangedBlocks(), sink, budget, world);
			handle = backend.start(request, tools);
			handle.outcome().thenAccept(outcome -> server.execute(() -> backendEnded(outcome)));
		}

		void cancel() {
			cancelled = true;
			handle.cancel();
			job.cancel();
		}

		private void briefed(Brief brief) {
			StoredBuild titled = new StoredBuild(stored.dimension(), brief.title(), journal);
			store.replace(owner, stored, titled);
			stored = titled;
			tell(owner, Component.literal(Messages.designing(brief)));
		}

		/** Claude (here, the script) is done: whatever it left queued still goes in, then the job ends. */
		private void backendEnded(BuildOutcome outcome) {
			if (outcome == BuildOutcome.FAILED) {
				failure = handle.failure();
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
			// A build that changed nothing leaves the history, so undo never answers with nothing restored.
			boolean undoable = journal.size() > 0;
			if (undoable) {
				store.setDirty();
			} else {
				store.remove(owner, stored);
			}
			String line;
			if (error != null) {
				line = Messages.failed(reason(error));
			} else if (failure != null) {
				line = Messages.failed(failure);
			} else if (cancelled) {
				line = Messages.stopped(job.placed());
			} else {
				line = Messages.done(job.placed(), skipped);
			}
			ServerPlayer online = server.getPlayerList().getPlayer(owner);
			if (online != null) {
				// The last "Building …" would otherwise linger for seconds after the build is over.
				online.sendOverlayMessage(Component.empty());
			}
			if (error == null && failure == null && !cancelled && !summary.isBlank()) {
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
