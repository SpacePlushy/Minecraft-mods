package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import io.palmisano.aibuilder.core.tools.WorldReader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
	private final Supplier<BlockPos> player;
	private final int siteMinX;
	private final int siteMinZ;
	private final int siteSize;
	private final CompletableFuture<WorldSurvey.Survey> site;
	private final UndoJournal<BlockState, CompoundTag> journal;
	private final ItemCatalog items;

	/**
	 * Server thread. {@code player} gives the player's position, or null when there's no one; {@code journal} is this
	 * build's own, for check_build; {@code config} says whether player-made blocks are protected and what's banned.
	 */
	public McWorldReader(ServerLevel level, LocalFrame frame, Box allowed, SizeTier tier, Supplier<BlockPos> player,
			List<UndoJournal<BlockState, CompoundTag>> earlier, UndoJournal<BlockState, CompoundTag> journal, AiBuilderConfig config) {
		this.level = level;
		this.frame = frame;
		this.allowed = allowed;
		this.player = player;
		this.journal = journal;
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

	@Override
	public WorldFacts facts() {
		return onServer(() -> {
			BlockPos origin = Positions.toBlockPos(frame.origin());
			String dimension = level.dimension().identifier().toString();
			String biome = level.getBiome(origin).unwrapKey().map(key -> key.identifier().toString()).orElse("unknown");
			String time = dimension.equals("minecraft:overworld") ? timeOfDay(level.getOverworldClockTime() % 24_000) : "no day or night here";
			String weather = level.isThundering() ? "a thunderstorm" : level.isRaining() ? "raining" : "clear";
			BlockPos at = player.get();
			Pos local = at == null ? new Pos(0, 1, -Tunables.SITE_GAP) : frame.toLocal(Positions.toPos(at));
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

	/** Read over ticks on the server (BuildCheck), waited for here. */
	@Override
	public List<BuildProblems.Problem> check() {
		MinecraftServer server = level.getServer();
		CompletableFuture<List<BuildProblems.Problem>> check = ServerHop.submit(server, server::isSameThread, server::isStopped, () -> {
			BuildCheck reading = new BuildCheck(level, frame, allowed, journal, Tunables.TICK_BUDGET_NANOS);
			JobScheduler.add(reading);
			return reading;
		}).thenCompose(BuildCheck::result);
		return await(check, Tunables.CHECK_BUILD_TIMEOUT_SECONDS);
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

	private static Rotation inverse(Rotation rotation) {
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
