package io.palmisano.aibuilder.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.mojang.datafixers.util.Pair;
import io.palmisano.aibuilder.AiBuilderMod;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.request.Messages;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.net.BuildPromptPayload;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The showcase world (Frankie, 2026-10-22): live builds with the real Claude Code on the owner's login, in a world with
 * real terrain, each in a biome that suits it, the world copied into the dev client's saves after every build (so
 * {@code ./gradlew runClient} lists it). Runs only with {@code AIBUILDER_LIVE=1 AIBUILDER_SHOWCASE=1}, and then it's
 * the only client gametest that does. Never touches the real game's folder.
 */
@SuppressWarnings("UnstableApiUsage")
public class ShowcaseClientGameTest implements FabricClientGameTest {
	static final String WORLD = "AI Builder Showcase";
	private static final String SEED = "aibuilder-showcase";
	/** The build's own time cap, and some. */
	private static final int MAX_TICKS = 20 * 60 * 65;
	private static final int SCREENSHOT_EVERY_TICKS = 20 * 60 * 3;
	/** Builds stay this far apart, so none's site overlaps another's. */
	private static final int APART = 160;
	/**
	 * The run's caps, above the defaults (150 tool calls, 500,000 output tokens, 45 minutes) for big builds: written to
	 * this run's config/aibuilder.json and put back after. The usage-limit and extra-usage stops hold as ever.
	 */
	private static final String CAPS = "{\"max_tool_iterations\": 260, \"max_tokens_per_build\": 900000, \"max_build_minutes\": 60}";

	/** One build: where (a biome, or "" for the world spawn), what, how big; {@code refines}: a follow-up prompt in place. */
	record Build(String name, String biome, String prompt, SizeTier size, String refines) {
	}

	static boolean showcase() {
		return "1".equals(System.getenv("AIBUILDER_SHOWCASE"));
	}

	/** The builds, from AIBUILDER_SHOWCASE_ONLY (names, comma-separated) when given. */
	static List<Build> builds() {
		List<Build> all = List.of(
				new Build("castle", "minecraft:plains", "a grand medieval castle with a gatehouse, round towers, a keep with a great hall,"
						+ " banners and a courtyard with a well", SizeTier.LARGE, "add a moat around the castle with a drawbridge at the gate"),
				new Build("temple", "minecraft:cherry_grove", "a Japanese temple with a five-storey pagoda, a koi pond with a red bridge, stone"
						+ " lanterns and a raked gravel garden", SizeTier.MEDIUM, ""),
				new Build("lighthouse", "minecraft:stony_shore", "a lighthouse on the rocks by the sea, striped red and white, with a glowing"
						+ " lamp room on top and a keeper's cottage", SizeTier.MEDIUM, ""),
				new Build("bazaar", "minecraft:desert", "a desert bazaar: market stalls with colourful awnings, a domed sandstone palace, an oasis"
						+ " with palm trees", SizeTier.MEDIUM, ""),
				new Build("tower", "minecraft:dark_forest", "a wizard's tower in the dark forest: crooked and tall, with a spiral staircase, a"
						+ " library, glowing windows and a telescope on the roof", SizeTier.MEDIUM, ""));
		String only = System.getenv("AIBUILDER_SHOWCASE_ONLY");
		if (only == null || only.isBlank()) {
			return all;
		}
		List<String> names = List.of(only.split(","));
		return all.stream().filter(b -> names.contains(b.name())).toList();
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!TestSetup.live() || !showcase()) {
			return;
		}
		try (TestSingleplayerContext singleplayer = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(s -> {
			s.setName(WORLD);
			s.setSeed(SEED);
			s.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			s.setAllowCommands(true);
		}).create()) {
			TestServerContext server = singleplayer.getServer();
			for (String command : List.of("time set noon", "gamerule advance_time false", "gamerule advance_weather false", "weather clear",
					"difficulty peaceful", "gamerule spawn_mobs false")) {
				server.runCommand(command);
			}
			Path config = FabricLoader.getInstance().getConfigDir().resolve("aibuilder.json");
			String before = Files.exists(config) ? Files.readString(config) : null;
			Files.writeString(config, CAPS);
			try {
				List<String> tour = new ArrayList<>();
				List<BlockPos> taken = new ArrayList<>();
				for (Build build : builds()) {
					Spot spot = server.computeOnServer(s -> find(s.overworld(), build.biome(), build.size(), taken));
					if (spot == null) {
						AiBuilderMod.LOG.warn("Showcase: no {} found for the {}; skipped", build.biome(), build.name());
						continue;
					}
					if (taken.isEmpty()) {
						// The world opens at the first build.
						server.runCommand("setworldspawn " + spot.pos().getX() + " " + spot.pos().getY() + " " + spot.pos().getZ());
					}
					taken.add(spot.pos());
					run(ctx, singleplayer, server, build, spot, build.prompt());
					if (!build.refines().isEmpty()) {
						// Back where the first prompt was given, so the refinement picks up the same build.
						run(ctx, singleplayer, server, build, spot, build.refines());
					}
					tour.add(build.name() + " at x " + spot.pos().getX() + ", y " + spot.pos().getY() + ", z " + spot.pos().getZ());
					AiBuilderMod.LOG.info("Showcase tour so far: {}", tour);
					keep(server, singleplayer);
				}
			} finally {
				if (before == null) {
					Files.deleteIfExists(config);
				} else {
					Files.writeString(config, before);
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Where a build goes: on the ground, facing the way with the flattest dry land ahead. */
	record Spot(BlockPos pos, int yaw) {
	}

	private static Spot find(ServerLevel level, String biome, SizeTier size, List<BlockPos> taken) {
		BlockPos spawn = level.getRespawnData().pos();
		ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, Identifier.parse(biome));
		BlockPos from = spawn;
		for (int attempt = 0; attempt < 6; attempt++) {
			Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(h -> h.is(key), from, 4000, 32, 64);
			if (found == null) {
				return null;
			}
			BlockPos at = found.getFirst();
			if (taken.stream().allMatch(t -> t.distSqr(at) > (double) APART * APART)) {
				return facing(level, at, size);
			}
			// Too close to an earlier build: look again from further out.
			from = at.offset((attempt + 1) * APART, 0, (attempt % 2 == 0 ? 1 : -1) * APART);
		}
		return null;
	}

	private static Spot facing(ServerLevel level, BlockPos at, SizeTier size) {
		int w = size.size();
		Spot best = null;
		int bestScore = Integer.MAX_VALUE;
		for (int yaw : new int[] {0, 90, 180, 270}) {
			// Forward for yaw 0 is south (+z), 90 west (-x), 180 north, 270 east.
			int fx = yaw == 90 ? -1 : yaw == 270 ? 1 : 0;
			int fz = yaw == 0 ? 1 : yaw == 180 ? -1 : 0;
			int min = Integer.MAX_VALUE;
			int max = Integer.MIN_VALUE;
			int water = 0;
			for (int f = Tunables.SITE_GAP; f < Tunables.SITE_GAP + w; f += Math.max(1, w / 6)) {
				for (int s = -w / 2; s < w / 2; s += Math.max(1, w / 6)) {
					int x = at.getX() + fx * f + fz * s;
					int z = at.getZ() + fz * f - fx * s;
					level.getChunk(x >> 4, z >> 4);
					int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
					if (!level.getFluidState(new BlockPos(x, y, z)).isEmpty()) {
						water++;
					}
					min = Math.min(min, y);
					max = Math.max(max, y);
				}
			}
			int score = water * 12 + (max - min);
			if (score < bestScore) {
				bestScore = score;
				level.getChunk(at.getX() >> 4, at.getZ() >> 4);
				best = new Spot(new BlockPos(at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()), at.getZ()), yaw);
			}
		}
		return best;
	}

	/** On the ground at the spot, facing its way, with the chunks drawn. */
	private static void stand(ClientGameTestContext ctx, TestSingleplayerContext singleplayer, TestServerContext server, Spot spot) {
		server.runCommand("gamemode creative @a");
		server.runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f %d 0", spot.pos().getX() + 0.5, spot.pos().getY(), spot.pos().getZ() + 0.5,
				spot.yaw()));
		ctx.waitFor(mc -> mc.player != null && Math.abs(mc.player.getX() - (spot.pos().getX() + 0.5)) < 0.01
				&& Math.abs(mc.player.getZ() - (spot.pos().getZ() + 0.5)) < 0.01, 400);
		ctx.waitTicks(20);
		singleplayer.getConnection().waitForChunksRender();
	}

	private static void run(ClientGameTestContext ctx, TestSingleplayerContext singleplayer, TestServerContext server, Build build, Spot spot,
			String prompt) {
		stand(ctx, singleplayer, server, spot);
		boolean first = prompt.equals(build.prompt());
		String label = build.name() + (first ? "" : "-refined");
		int from = GameTestSupport.chat().size();
		// A new build at a new site, far from the last; the follow-up refines it (spec §5.4).
		ctx.runOnClient(mc -> ClientPlayNetworking.send(new BuildPromptPayload(prompt, build.size(), first)));
		GameTestSupport.awaitChatStartingWith(ctx, first ? Messages.accepted(prompt, build.size()) : "AI Builder: changing ", 200);
		String end = GameTestSupport.awaitBuildEnd(ctx, from, MAX_TICKS, SCREENSHOT_EVERY_TICKS,
				n -> ctx.takeScreenshot("showcase-" + label + "-progress-" + n));
		AiBuilderMod.LOG.info("Showcase {} ended: {}", label, end);
		AiBuilderMod.LOG.info("Showcase {} chat: {}", label, GameTestSupport.chat().subList(from, GameTestSupport.chat().size()));
		ctx.waitTicks(40);
		ctx.takeScreenshot("showcase-" + label + "-player-view");
		views(ctx, singleplayer, server, build.size(), spot, label);
	}

	/** From the front, the front-left and the side, looking at the middle of the site, as a spectator. */
	private static void views(ClientGameTestContext ctx, TestSingleplayerContext singleplayer, TestServerContext server, SizeTier size, Spot spot,
			String label) {
		server.runCommand("gamemode spectator @a");
		ctx.waitTicks(200);
		int span = size.size();
		double yaw = Math.toRadians(spot.yaw());
		// The forward and right unit vectors for the spot's facing.
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		double rx = -fz;
		double rz = fx;
		double mid = Tunables.SITE_GAP + span / 2.0;
		double cx = spot.pos().getX() + 0.5 + fx * mid;
		double cz = spot.pos().getZ() + 0.5 + fz * mid;
		int y = spot.pos().getY();
		String look = String.format(Locale.ROOT, " facing %.1f %d %.1f", cx, y + span / 8, cz);
		// {back, right, up}: behind and above; front-left; the left side (right is negative: the player's left).
		double[][] views = {{-span * 0.5, 0, span * 0.66}, {-span * 0.2, -span * 0.75, span * 0.36}, {mid, -span * 1.1, span / 3.0}};
		String[] names = {"overview", "front-left", "side"};
		for (int i = 0; i < views.length; i++) {
			double back = views[i][0];
			double right = views[i][1];
			double x = spot.pos().getX() + 0.5 + fx * back + rx * right;
			double z = spot.pos().getZ() + 0.5 + fz * back + rz * right;
			if (i == 2) {
				x = cx + rx * right;
				z = cz + rz * right;
			}
			server.runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f", x, y + Math.round(views[i][2]), z) + look);
			ctx.waitTicks(80);
			singleplayer.getConnection().waitForChunksRender();
			ctx.takeScreenshot("showcase-" + label + "-" + names[i]);
		}
		server.runCommand("gamemode creative @a");
	}

	/** The world, saved and copied into the dev client's saves ({@code run/saves}), replacing the last copy. */
	private static void keep(TestServerContext server, TestSingleplayerContext singleplayer) {
		server.runCommand("save-all flush");
		Path from = singleplayer.getWorldSave().getSaveDirectory();
		Path to = FabricLoader.getInstance().getGameDir().resolve("../../../run/saves").normalize().resolve(WORLD);
		try {
			if (Files.exists(to)) {
				try (Stream<Path> old = Files.walk(to)) {
					for (Path p : old.sorted(Comparator.reverseOrder()).toList()) {
						Files.delete(p);
					}
				}
			}
			try (Stream<Path> files = Files.walk(from)) {
				for (Path p : files.toList()) {
					if (p.getFileName().toString().equals("session.lock")) {
						continue;
					}
					Path target = to.resolve(from.relativize(p).toString());
					if (Files.isDirectory(p)) {
						Files.createDirectories(target);
					} else {
						Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
			AiBuilderMod.LOG.info("Showcase world copied to {}", to);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
