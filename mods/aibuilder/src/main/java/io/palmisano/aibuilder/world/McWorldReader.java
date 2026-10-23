package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.blocks.ItemCatalog;
import io.palmisano.aibuilder.core.config.AiBuilderConfig;
import io.palmisano.aibuilder.core.frame.Directions;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.FireMask;
import io.palmisano.aibuilder.core.place.ProtectionMask;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.site.SiteSquare;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.survey.BuildProblems;
import io.palmisano.aibuilder.core.survey.Column;
import io.palmisano.aibuilder.core.survey.TerrainSnapshot;
import io.palmisano.aibuilder.core.tools.ServerHop;
import io.palmisano.aibuilder.core.tools.WorldFacts;
import io.palmisano.aibuilder.core.tools.RedstoneSteps;
import io.palmisano.aibuilder.core.tools.WorldReader;
import io.palmisano.aibuilder.core.wire.Cell;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The world as one build's tools see it. Made on the server thread when the build starts, it starts reading the site
 * at once (the survey area, and the protection mask over the allowed box); the tools then call in from the
 * backend's thread, and every read happens on the server thread, waited for with a timeout.
 */
public final class McWorldReader implements WorldReader {
	private final ServerLevel level;
	private final LocalFrame frame;
	private final Box allowed;
	private final Supplier<ServerPlayer> player;
	/** The check_build read under way, or the last one; guarded by this. */
	private CompletableFuture<List<BuildProblems.Problem>> checking;
	/** A test_redstone is running. */
	private final AtomicBoolean testing = new AtomicBoolean();
	private final int siteMinX;
	private final int siteMinZ;
	private final int siteSize;
	private final CompletableFuture<WorldSurvey.Survey> site;
	private final UndoJournal<BlockState, CompoundTag> journal;
	private final ItemCatalog items;

	/**
	 * Server thread. {@code player} gives the build's owner, or null when they're offline; {@code journal} is this
	 * build's own, for check_build; {@code config} says whether player-made blocks are protected and what's banned.
	 */
	private volatile PlacementJob job;
	/** The player's earlier builds here: theirs to change, and a machine's to move as its own. */
	private final List<UndoJournal<BlockState, CompoundTag>> earlier;

	public McWorldReader(ServerLevel level, LocalFrame frame, Box allowed, SizeTier tier, Supplier<ServerPlayer> player,
			List<UndoJournal<BlockState, CompoundTag>> earlier, UndoJournal<BlockState, CompoundTag> journal, AiBuilderConfig config) {
		this.level = level;
		this.frame = frame;
		this.allowed = allowed;
		this.player = player;
		this.journal = journal;
		this.earlier = List.copyOf(earlier);
		this.items = new McItemCatalog(level.registryAccess(), Set.copyOf(config.bannedBlocks()));
		// The whole allowed box and past it, a refinement's grown one too: protection and the lava rule hold to its edge.
		SiteSquare square = SiteSquare.around(allowed, tier, Tunables.SITE_MARGIN);
		this.siteMinX = square.minX();
		this.siteMinZ = square.minZ();
		this.siteSize = square.size();
		WorldSurvey survey = new WorldSurvey(level, frame, allowed, siteMinX, siteMinZ, siteSize, siteSize, true, config.protectPlayerBlocks(),
				earlier);
		JobScheduler.add(survey);
		this.site = survey.result();
	}

	/** The site's survey and protection, completed on the server thread once read; for waiting without blocking. */
	public CompletableFuture<WorldSurvey.Survey> whenRead() {
		return site;
	}

	@Override
	public TerrainSnapshot terrain(int minX, int minZ, int width, int depth) {
		if (minX >= siteMinX && minZ >= siteMinZ && minX + width <= siteMinX + siteSize && minZ + depth <= siteMinZ + siteSize) {
			TerrainSnapshot whole = await(site, Tunables.SURVEY_TIMEOUT_SECONDS).terrain();
			List<Column> part = new ArrayList<>(width * depth);
			for (int z = minZ; z < minZ + depth; z++) {
				for (int x = minX; x < minX + width; x++) {
					part.add(whole.at(x, z));
				}
			}
			return new TerrainSnapshot(minX, minZ, width, depth, part);
		}
		// Bigger than the site: one more survey, without protection of its own. Protection is only ever about the
		// allowed box, which the site covers, so the wider one shows the site's: writes there are refused all the same.
		TerrainSnapshot wider = survey(minX, minZ, width, depth);
		TerrainSnapshot siteTerrain = await(site, Tunables.SURVEY_TIMEOUT_SECONDS).terrain();
		List<Column> columns = new ArrayList<>(wider.columns().size());
		for (int z = minZ; z < minZ + depth; z++) {
			for (int x = minX; x < minX + width; x++) {
				Column c = wider.at(x, z);
				if (siteTerrain.contains(x, z) && siteTerrain.at(x, z).protectedCount() > 0) {
					Column s = siteTerrain.at(x, z);
					c = new Column(c.floor(), c.top(), c.ceiling(), c.floorBlock(), c.water(), c.lava(), c.tree(), s.protectedCount(), s.protectedBlock());
				}
				columns.add(c);
			}
		}
		return new TerrainSnapshot(minX, minZ, width, depth, columns);
	}

	@Override
	public TerrainSnapshot currentTerrain(int minX, int minZ, int width, int depth) {
		return survey(minX, minZ, width, depth);
	}

	/** A fresh survey of a local area, without protection: read over ticks on the server, waited for here. */
	private TerrainSnapshot survey(int minX, int minZ, int width, int depth) {
		MinecraftServer server = level.getServer();
		CompletableFuture<WorldSurvey.Survey> survey = ServerHop.submit(server, server::isSameThread, server::isStopped, () -> {
			WorldSurvey reading = new WorldSurvey(level, frame, allowed, minX, minZ, width, depth, false, false, List.of());
			JobScheduler.add(reading);
			return reading;
		}).thenCompose(WorldSurvey::result);
		return await(survey, Tunables.SURVEY_TIMEOUT_SECONDS).terrain();
	}

	@Override
	public List<String> states(Box local) {
		return onServer(() -> {
			Rotation back = inverse(StateFactory.rotation(frame.facing()));
			List<String> out = new ArrayList<>();
			for (int y = local.min().y(); y <= local.max().y(); y++) {
				for (int z = local.min().z(); z <= local.max().z(); z++) {
					for (int x = local.min().x(); x <= local.max().x(); x++) {
						BlockPos pos = Positions.toBlockPos(frame.toWorld(new Pos(x, y, z)));
						// Only what's loaded: reading an unloaded chunk would load (or generate) it right here, on the tick.
						LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
						out.add(chunk == null ? "not_loaded" : describe(chunk.getBlockState(pos).rotate(back)));
					}
				}
			}
			return out;
		});
	}

	/** Only loaded chunks: a position in another reads as not solid. Something you'd walk into is solid. */
	@Override
	public Set<Pos> solid(Set<Pos> local) {
		return onServer(() -> {
			Set<Pos> out = new HashSet<>();
			for (Pos at : local) {
				BlockPos pos = Positions.toBlockPos(frame.toWorld(at));
				LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
				if (chunk != null && !chunk.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
					out.add(at);
				}
			}
			return out;
		});
	}

	@Override
	public Map<Pos, Cell> cells(Set<Pos> local) {
		return onServer(() -> {
			Map<Pos, Cell> out = new HashMap<>();
			for (Pos at : local) {
				BlockPos pos = Positions.toBlockPos(frame.toWorld(at));
				LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
				out.put(at, chunk == null ? Cell.OTHER : cell(chunk.getBlockState(pos), pos));
			}
			return out;
		});
	}

	/** A block as a wire sees it: dust, other redstone, free (air or what a block takes the place of, but water), ground, or else. */
	private Cell cell(BlockState state, BlockPos pos) {
		if (state.is(net.minecraft.world.level.block.Blocks.REDSTONE_WIRE)) {
			return Cell.DUST;
		}
		if (Redstone.component(state)) {
			return Cell.COMPONENT;
		}
		if (state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty()) {
			return Cell.FREE;
		}
		return state.isRedstoneConductor(level, pos) && state.isFaceSturdy(level, pos, Direction.UP) ? Cell.SOLID : Cell.OTHER;
	}

	@Override
	public WorldFacts facts() {
		return onServer(() -> {
			BlockPos origin = Positions.toBlockPos(frame.origin());
			String dimension = level.dimension().identifier().toString();
			String biome = level.getBiome(origin).unwrapKey().map(key -> key.identifier().toString()).orElse("unknown");
			String time = dimension.equals("minecraft:overworld") ? timeOfDay(level.getOverworldClockTime() % 24_000) : "no day or night here";
			String weather = level.isThundering() ? "a thunderstorm" : level.isRaining() ? "raining" : "clear";
			ServerPlayer online = player.get();
			// Offline, or in another dimension: nowhere in this build's frame.
			Pos local = online == null || online.level() != level ? null : frame.toLocal(Positions.toPos(online.blockPosition()));
			return new WorldFacts(dimension, biome, time, weather, id(level.getBlockState(origin)), local);
		});
	}

	@Override
	public ProtectionMask protection() {
		return await(site, Tunables.SURVEY_TIMEOUT_SECONDS).protection();
	}

	@Override
	public FireMask fire() {
		return await(site, Tunables.SURVEY_TIMEOUT_SECONDS).fire();
	}

	@Override
	public ItemCatalog items() {
		return items;
	}

	/**
	 * Read over ticks on the server (BuildCheck), waited for here. One at a time: a call while a check is still reading
	 * (a retry after a timeout) waits on that one instead of starting another over the same build.
	 */
	@Override
	public List<BuildProblems.Problem> check() {
		CompletableFuture<List<BuildProblems.Problem>> check;
		synchronized (this) {
			if (checking == null || checking.isDone()) {
				MinecraftServer server = level.getServer();
				checking = ServerHop.submit(server, server::isSameThread, server::isStopped, () -> {
					BuildCheck reading = new BuildCheck(level, frame, allowed, journal, earlier, Tunables.TICK_BUDGET_NANOS);
					if (job != null) {
						reading.started(job.started());
					}
					JobScheduler.add(reading);
					return reading;
				}).thenCompose(BuildCheck::result);
			}
			check = checking;
		}
		return await(check, Tunables.CHECK_BUILD_TIMEOUT_SECONDS);
	}

	/** The build's job, whose machine test_redstone tries (spec v2 §5.2). Before the build starts. */
	public void machine(PlacementJob job) {
		this.job = job;
	}

	@Override
	public String testRedstone(List<RedstoneSteps.Step> steps) {
		if (job == null || job.machines() == null) {
			throw new IllegalArgumentException("test_redstone has no machine to try here");
		}
		// One at a time (M8 review): two at once would each take the other's flips for how the levers were.
		if (!testing.compareAndSet(false, true)) {
			throw new IllegalArgumentException("test_redstone is already trying this build's machine: wait for that answer, then test again"
					+ " (one test at a time)");
		}
		try {
			MinecraftServer server = level.getServer();
			PlacementJob machine = job;
			CompletableFuture<RedstoneTest> started = ServerHop.submit(server, server::isSameThread, server::isStopped, () -> {
				RedstoneTest running = new RedstoneTest(level, frame, journal, machine, steps);
				JobScheduler.add(running);
				return running;
			});
			RedstoneTest.Outcome outcome;
			try {
				outcome = await(started.thenCompose(RedstoneTest::result), Tunables.TEST_REDSTONE_TIMEOUT_SECONDS);
			} catch (Unreadable e) {
				// No one reads its answer now: it stops, its levers back.
				if (started.isDone() && !started.isCompletedExceptionally()) {
					started.join().stop();
				}
				throw e;
			}
			if (outcome.refused()) {
				throw new IllegalArgumentException(outcome.text());
			}
			return outcome.text();
		} finally {
			testing.set(false);
		}
	}

	/** {@code id[prop=value,…]}, compass names and values as relative words; the state already turned back to the template. */
	static String describe(BlockState state) {
		String properties = state.getValues()
				.map(value -> {
					String name = value.property().getName();
					return (Directions.isCompassToken(name) ? Directions.toLocalToken(name) : name) + "=" + Directions.toLocalValue(value.valueName());
				})
				.collect(Collectors.joining(","));
		return id(state) + (properties.isEmpty() ? "" : "[" + properties + "]");
	}

	private static String timeOfDay(long ticks) {
		if (ticks < 6_000) {
			return "morning";
		}
		if (ticks < 12_000) {
			return "afternoon";
		}
		if (ticks < 13_000) {
			return "sunset";
		}
		return ticks < 23_000 ? "night" : "sunrise";
	}

	static Rotation inverse(Rotation rotation) {
		return switch (rotation) {
			case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
			case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
			default -> rotation;
		};
	}

	/** Runs a read on the server thread and waits for it; on the server thread itself, just runs it. */
	private <T> T onServer(Supplier<T> read) {
		MinecraftServer server = level.getServer();
		if (server.isSameThread()) {
			return read.get();
		}
		return await(ServerHop.submit(server, server::isSameThread, server::isStopped, read), Tunables.READ_TIMEOUT_SECONDS);
	}

	private <T> T await(CompletableFuture<T> future, int seconds) {
		if (!future.isDone() && level.getServer().isSameThread()) {
			throw new Unreadable("the world is read on the server's tick, which can't wait for itself");
		}
		try {
			return future.get(seconds, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			throw new Unreadable("reading it took longer than " + seconds + " seconds");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new Unreadable("interrupted");
		} catch (ExecutionException e) {
			throw new Unreadable(e.getCause() == null ? "it failed" : String.valueOf(e.getCause().getMessage()));
		}
	}

	private static String id(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}
}
