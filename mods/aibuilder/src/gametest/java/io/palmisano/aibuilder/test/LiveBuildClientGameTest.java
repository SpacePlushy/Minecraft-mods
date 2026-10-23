package io.palmisano.aibuilder.test;

import java.util.UUID;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import io.palmisano.aibuilder.world.UndoStore;
import io.palmisano.aibuilder.world.Positions;
import io.palmisano.aibuilder.world.AnimationStore;
import io.palmisano.aibuilder.core.anim.Motion;
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

/**
 * A live build with the real Claude Code on the owner's own login. Never in the suites (spec §17.5): it runs only with
 * {@code AIBUILDER_LIVE=1} on the Gradle command, and then it's the only client gametest that does:
 * {@code AIBUILDER_LIVE=1 ./gradlew runClientGameTest --no-daemon}. {@code AIBUILDER_LIVE_PROMPT} and
 * {@code AIBUILDER_LIVE_SIZE} pick the build (a small cozy cabin, Small); {@code AIBUILDER_LIVE_REFINE}, a second
 * prompt that refines it from where the player stood ("add a moat", spec §5.4); {@code AIBUILDER_LIVE_CAPS}, the run's
 * caps as config/aibuilder.json takes them (a calculator: {"max_tool_iterations": 400, "max_build_minutes": 90}), written
 * for the run and put back after. It saves screenshots along the way and from three viewpoints after each prompt; the
 * logs are in build/run/clientGameTest/logs/aibuilder/.
 */
@SuppressWarnings("UnstableApiUsage")
public class LiveBuildClientGameTest implements FabricClientGameTest {
	/** The build's own time cap is 45 minutes unless the run's caps say otherwise; this waits 5 more. */
	private static final int MINUTES = 45;
	private static final int SCREENSHOT_EVERY_TICKS = 20 * 60 * 2;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!TestSetup.live() || ShowcaseClientGameTest.showcase()) {
			return; // a showcase run is for ShowcaseClientGameTest alone
		}
		String prompt = System.getenv().getOrDefault("AIBUILDER_LIVE_PROMPT", "a small cozy cabin");
		SizeTier size = SizeTier.parse(System.getenv().getOrDefault("AIBUILDER_LIVE_SIZE", "small"));
		String refine = System.getenv().getOrDefault("AIBUILDER_LIVE_REFINE", "");
		String caps = System.getenv().getOrDefault("AIBUILDER_LIVE_CAPS", "");
		java.nio.file.Path config = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("aibuilder.json");
		String before;
		try {
			before = java.nio.file.Files.exists(config) ? java.nio.file.Files.readString(config) : null;
			if (!caps.isEmpty()) {
				java.nio.file.Files.writeString(config, caps);
			}
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
		int minutes = caps.isEmpty() ? MINUTES : com.google.gson.JsonParser.parseString(caps).getAsJsonObject().has("max_build_minutes")
				? com.google.gson.JsonParser.parseString(caps).getAsJsonObject().get("max_build_minutes").getAsInt() : MINUTES;
		int maxTicks = 20 * 60 * (minutes + 5);
		try {
			run(ctx, prompt, size, refine, maxTicks);
		} finally {
			try {
				if (!caps.isEmpty()) {
					if (before == null) {
						java.nio.file.Files.deleteIfExists(config);
					} else {
						java.nio.file.Files.writeString(config, before);
					}
				}
			} catch (java.io.IOException e) {
				AiBuilderMod.LOG.warn("Couldn't put config/aibuilder.json back: {}", e.toString());
			}
		}
	}

	private static void run(ClientGameTestContext ctx, String prompt, SizeTier size, String refine, int maxTicks) {
		try (TestSingleplayerContext singleplayer = GameTestSupport.world(ctx)) {
			TestServerContext server = singleplayer.getServer();
			// Noon for the whole build, no weather, nothing hostile walking up to the player.
			server.runCommand("time set noon");
			server.runCommand("gamerule advance_time false");
			server.runCommand("gamerule advance_weather false");
			server.runCommand("weather clear");
			server.runCommand("difficulty peaceful");
			server.runCommand("tp @a 0.5 ~ 0.5 0 0");
			ctx.waitFor(mc -> mc.player != null && mc.player.getYRot() == 0 && Math.abs(mc.player.getZ() - 0.5) < 0.01);
			int feetY = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getBlockY());

			ctx.runOnClient(mc -> ClientPlayNetworking.send(new BuildPromptPayload(prompt, size)));
			GameTestSupport.awaitChat(ctx, Messages.accepted(prompt, size), 200);
			String end = GameTestSupport.awaitBuildEnd(ctx, 0, maxTicks, SCREENSHOT_EVERY_TICKS, n -> ctx.takeScreenshot("aibuilder-live-progress-" + n));
			AiBuilderMod.LOG.info("Live build ended: {}", end);
			AiBuilderMod.LOG.info("Live build chat: {}", GameTestSupport.chat());
			ctx.waitTicks(40);
			ctx.takeScreenshot("aibuilder-live-player-view");
			int span = size == SizeTier.AUTO ? SizeTier.LARGE.size() : size.size();
			viewpoints(ctx, singleplayer, feetY, span, refine.isEmpty() ? "" : "-before");
			moments(ctx, singleplayer, feetY, span, refine.isEmpty() ? "" : "-before");
			machine(ctx, singleplayer, feetY, span, refine.isEmpty() ? "" : "-before");
			calculator(ctx, singleplayer, refine.isEmpty() ? "" : "-before");
			if (refine.isEmpty()) {
				return;
			}

			// Back where the player stood, on foot: a prompt within 48 blocks of the build changes it (spec §5.4).
			server.runCommand("gamemode creative @a");
			server.runCommand("tp @a 0.5 " + feetY + " 0.5 0 0");
			ctx.waitTicks(40);
			int from = GameTestSupport.chat().size();
			ctx.runOnClient(mc -> ClientPlayNetworking.send(new BuildPromptPayload(refine, size, false)));
			GameTestSupport.awaitChatStartingWith(ctx, "AI Builder: changing ", 200);
			String refined = GameTestSupport.awaitBuildEnd(ctx, from, maxTicks, SCREENSHOT_EVERY_TICKS,
					n -> ctx.takeScreenshot("aibuilder-live-refine-progress-" + n));
			AiBuilderMod.LOG.info("Live refinement ended: {}", refined);
			AiBuilderMod.LOG.info("Live refinement chat: {}", GameTestSupport.chat().subList(from, GameTestSupport.chat().size()));
			ctx.waitTicks(40);
			ctx.takeScreenshot("aibuilder-live-refined-player-view");
			// The refinement may build 16 blocks past the first build on every side: the views stand farther off.
			viewpoints(ctx, singleplayer, feetY, span + 24, "-refined");
			moments(ctx, singleplayer, feetY, span + 24, "-refined");
			machine(ctx, singleplayer, feetY, span + 24, "-refined");
			calculator(ctx, singleplayer, "-refined");
		}
	}

	/**
	 * A calculator (the M9 plan's check): found from the session's gadgets (two lever rows and a number), it's set to
	 * 0 + 0, 6 + 3, 9 + 7, 12 + 5 and 15 + 15 in turn, each waited out and read off its digits by this test itself, and
	 * pictured; the verdict is logged, "it works" only if all five are right. Nothing for a build with no calculator.
	 */
	private static void calculator(ClientGameTestContext ctx, TestSingleplayerContext singleplayer, String suffix) {
		TestServerContext server = singleplayer.getServer();
		UUID owner = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getUUID());
		java.util.Optional<FoundCalculator> found = server.computeOnServer(s -> io.palmisano.aibuilder.world.SessionStore.get(s).get(owner)
				.flatMap(FoundCalculator::of));
		if (found.isEmpty()) {
			return;
		}
		FoundCalculator calculator = found.get();
		server.runCommand("gamemode spectator @a");
		io.palmisano.aibuilder.core.frame.Pos[] view = calculator.view();
		io.palmisano.aibuilder.core.frame.Pos from = calculator.frame().toWorld(view[0]);
		io.palmisano.aibuilder.core.frame.Pos at = calculator.frame().toWorld(view[1]);
		server.runCommand("tp @a " + (from.x() + 0.5) + " " + from.y() + " " + (from.z() + 0.5) + " facing " + (at.x() + 0.5) + " " + (at.y() + 0.5)
				+ " " + (at.z() + 0.5));
		ctx.waitTicks(80);
		singleplayer.getConnection().waitForChunksRender();
		List<String> sums = new java.util.ArrayList<>();
		boolean works = true;
		for (int[] sum : new int[][] {{0, 0}, {6, 3}, {9, 7}, {12, 5}, {15, 15}}) {
			server.runOnServer(s -> calculator.set(s, sum[0], sum[1]));
			ctx.waitTicks(calculator.settle());
			String shown = server.computeOnServer(calculator::shows);
			ctx.takeScreenshot("aibuilder-live-calculator-" + sum[0] + "-plus-" + sum[1] + suffix);
			sums.add(sum[0] + " + " + sum[1] + " shows " + shown);
			works &= shown.equals(String.valueOf(sum[0] + sum[1]));
		}
		AiBuilderMod.LOG.info("Live calculator: {}: {}", String.join(", ", sums), works ? "it works" : "it doesn't work");
	}

	/**
	 * A build that works (spec v2 §5.6): its first lever flipped, then flipped back, pictured from the front-left each
	 * time. It works when every piston of the build changes and changes back; the verdict is logged. Nothing for a build
	 * with no lever.
	 */
	private static void machine(ClientGameTestContext ctx, TestSingleplayerContext singleplayer, int feetY, int span, String suffix) {
		TestServerContext server = singleplayer.getServer();
		UUID owner = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getUUID());
		List<BlockPos> levers = server.computeOnServer(s -> UndoStore.get(s).peek(owner)
				.map(build -> build.journal().levers().keySet().stream().map(Positions::toBlockPos).toList()).orElse(List.of()));
		if (levers.isEmpty()) {
			return;
		}
		List<BlockPos> pistons = server.computeOnServer(s -> UndoStore.get(s).peek(owner).map(build -> build.journal().positions().stream()
				.map(Positions::toBlockPos).filter(p -> s.overworld().getBlockState(p).getBlock() instanceof PistonBaseBlock).toList()).orElse(List.of()));
		double middleZ = Tunables.SITE_GAP + span / 2.0 - 0.5;
		String middle = " facing 0.5 " + (feetY + span / 8) + " " + middleZ;
		server.runCommand("tp @a " + (0.5 + Math.round(span * 0.75)) + " " + (feetY + Math.round(span * 0.36)) + " " + (0.5 - span / 5) + middle);
		ctx.waitTicks(80);
		singleplayer.getConnection().waitForChunksRender();
		List<Boolean> before = extended(server, pistons);
		ctx.takeScreenshot("aibuilder-live-machine-before" + suffix);
		BlockPos lever = levers.getFirst();
		server.runOnServer(s -> flip(s, lever));
		ctx.waitTicks(40);
		List<Boolean> flipped = extended(server, pistons);
		ctx.takeScreenshot("aibuilder-live-machine-flipped" + suffix);
		server.runOnServer(s -> flip(s, lever));
		ctx.waitTicks(40);
		List<Boolean> back = extended(server, pistons);
		ctx.takeScreenshot("aibuilder-live-machine-back" + suffix);
		boolean works = !pistons.isEmpty();
		for (int i = 0; i < pistons.size(); i++) {
			works &= before.get(i) != flipped.get(i) && back.get(i) == before.get(i);
		}
		AiBuilderMod.LOG.info("Live machine: lever {} of {}, {} pistons extended {} then {} then {}: {}", lever, levers.size(), pistons.size(), before,
				flipped, back, works ? "it works" : "it doesn't work");
	}

	private static List<Boolean> extended(TestServerContext server, List<BlockPos> pistons) {
		return server.computeOnServer(s -> pistons.stream().map(p -> {
			BlockState state = s.overworld().getBlockState(p);
			return state.getBlock() instanceof PistonBaseBlock && state.getValue(PistonBaseBlock.EXTENDED);
		}).toList());
	}

	/** Flips a lever, as a player's click does. */
	private static void flip(MinecraftServer server, BlockPos pos) {
		BlockState state = server.overworld().getBlockState(pos);
		if (state.getBlock() instanceof LeverBlock lever) {
			lever.pull(state, server.overworld(), pos, null);
		}
	}

	/**
	 * A build that moves (spec v2 §5.6), pictured from the front-left at the start, a quarter, half and three quarters of
	 * its longest cycle, so pictures half a cycle apart show its parts at both ends of their motion. Nothing for a build
	 * with no moving parts.
	 */
	private static void moments(ClientGameTestContext ctx, TestSingleplayerContext singleplayer, int feetY, int span, String suffix) {
		TestServerContext server = singleplayer.getServer();
		long[] cycle = server.computeOnServer(s -> AnimationStore.get(s).builds().stream().findFirst().map(build -> new long[] {build.key().start(),
				build.parts().stream().flatMap(part -> part.motion().motions().stream()).mapToInt(LiveBuildClientGameTest::period).max().orElse(0)})
				.orElse(new long[] {0, 0}));
		long start = cycle[0];
		long period = cycle[1];
		String moving = server.computeOnServer(s -> AnimationStore.get(s).builds().toString());
		AiBuilderMod.LOG.info("Live build's moving parts: {}", moving);
		if (period == 0) {
			return;
		}
		double middleZ = Tunables.SITE_GAP + span / 2.0 - 0.5;
		String middle = " facing 0.5 " + (feetY + span / 8) + " " + middleZ;
		server.runCommand("tp @a " + (0.5 + Math.round(span * 0.75)) + " " + (feetY + Math.round(span * 0.36)) + " " + (0.5 - span / 5) + middle);
		ctx.waitTicks(80);
		singleplayer.getConnection().waitForChunksRender();
		for (int quarter = 0; quarter < 4; quarter++) {
			long at = quarter * period / 4;
			server.waitFor(s -> Math.floorMod(s.overworld().getGameTime() - start, period) == at, (int) period + 20);
			ctx.takeScreenshot("aibuilder-live-moving-" + quarter + suffix);
		}
	}

	private static int period(Motion motion) {
		return Math.abs(switch (motion) {
			case Motion.Swing s -> s.period();
			case Motion.Spin s -> s.period();
			case Motion.Bob b -> b.period();
		});
	}

	/**
	 * Back and up, then from the front-left and the left side, each looking at the middle of the site, as far off as
	 * {@code span} (a Small site's middle is 15 blocks ahead, a Medium's 27). As a spectator, so nothing falls (the M5
	 * castle's player did, from 32 up), and once the chat has faded.
	 */
	private static void viewpoints(ClientGameTestContext ctx, TestSingleplayerContext singleplayer, int feetY, int span, String suffix) {
		TestServerContext server = singleplayer.getServer();
		server.runCommand("gamemode spectator @a");
		ctx.waitTicks(200);
		double middleZ = Tunables.SITE_GAP + span / 2.0 - 0.5;
		String middle = " facing 0.5 " + (feetY + span / 8) + " " + middleZ;
		server.runCommand("tp @a 0.5 " + (feetY + Math.round(span * 0.66)) + " " + (0.5 - span / 2) + middle);
		ctx.waitTicks(80);
		singleplayer.getConnection().waitForChunksRender();
		ctx.takeScreenshot("aibuilder-live-overview" + suffix);
		server.runCommand("tp @a " + (0.5 + Math.round(span * 0.75)) + " " + (feetY + Math.round(span * 0.36)) + " " + (0.5 - span / 5) + middle);
		ctx.waitTicks(80);
		singleplayer.getConnection().waitForChunksRender();
		ctx.takeScreenshot("aibuilder-live-front-left" + suffix);
		server.runCommand("tp @a " + (0.5 + Math.round(span * 1.1)) + " " + (feetY + span / 3) + " " + middleZ + middle);
		ctx.waitTicks(80);
		singleplayer.getConnection().waitForChunksRender();
		ctx.takeScreenshot("aibuilder-live-side" + suffix);
	}
}
