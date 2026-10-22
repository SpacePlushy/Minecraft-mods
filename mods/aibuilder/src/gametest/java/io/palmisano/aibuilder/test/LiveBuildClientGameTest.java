package io.palmisano.aibuilder.test;

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
 * prompt that refines it from where the player stood ("add a moat", spec §5.4). It saves screenshots along the way and
 * from three viewpoints after each prompt; the logs are in build/run/clientGameTest/logs/aibuilder/.
 */
@SuppressWarnings("UnstableApiUsage")
public class LiveBuildClientGameTest implements FabricClientGameTest {
	/** The build's own time cap is 45 minutes. */
	private static final int MAX_TICKS = 20 * 60 * 50;
	private static final int SCREENSHOT_EVERY_TICKS = 20 * 60 * 2;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!TestSetup.live() || ShowcaseClientGameTest.showcase()) {
			return; // a showcase run is for ShowcaseClientGameTest alone
		}
		String prompt = System.getenv().getOrDefault("AIBUILDER_LIVE_PROMPT", "a small cozy cabin");
		SizeTier size = SizeTier.parse(System.getenv().getOrDefault("AIBUILDER_LIVE_SIZE", "small"));
		String refine = System.getenv().getOrDefault("AIBUILDER_LIVE_REFINE", "");
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
			String end = GameTestSupport.awaitBuildEnd(ctx, 0, MAX_TICKS, SCREENSHOT_EVERY_TICKS, n -> ctx.takeScreenshot("aibuilder-live-progress-" + n));
			AiBuilderMod.LOG.info("Live build ended: {}", end);
			AiBuilderMod.LOG.info("Live build chat: {}", GameTestSupport.chat());
			ctx.waitTicks(40);
			ctx.takeScreenshot("aibuilder-live-player-view");
			int span = size == SizeTier.AUTO ? SizeTier.LARGE.size() : size.size();
			viewpoints(ctx, singleplayer, feetY, span, refine.isEmpty() ? "" : "-before");
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
			String refined = GameTestSupport.awaitBuildEnd(ctx, from, MAX_TICKS, SCREENSHOT_EVERY_TICKS,
					n -> ctx.takeScreenshot("aibuilder-live-refine-progress-" + n));
			AiBuilderMod.LOG.info("Live refinement ended: {}", refined);
			AiBuilderMod.LOG.info("Live refinement chat: {}", GameTestSupport.chat().subList(from, GameTestSupport.chat().size()));
			ctx.waitTicks(40);
			ctx.takeScreenshot("aibuilder-live-refined-player-view");
			// The refinement may build 16 blocks past the first build on every side: the views stand farther off.
			viewpoints(ctx, singleplayer, feetY, span + 24, "-refined");
		}
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
