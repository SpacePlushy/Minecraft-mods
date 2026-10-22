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
 * {@code AIBUILDER_LIVE_SIZE} pick the build (a small cozy cabin, Small). It saves screenshots along the way and from
 * two viewpoints at the end; the build's stream is in build/run/clientGameTest/logs/aibuilder/.
 */
@SuppressWarnings("UnstableApiUsage")
public class LiveBuildClientGameTest implements FabricClientGameTest {
	/** The build's own time cap is 45 minutes. */
	private static final int MAX_TICKS = 20 * 60 * 50;
	private static final int SCREENSHOT_EVERY_TICKS = 20 * 60 * 2;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!TestSetup.live()) {
			return;
		}
		String prompt = System.getenv().getOrDefault("AIBUILDER_LIVE_PROMPT", "a small cozy cabin");
		SizeTier size = SizeTier.parse(System.getenv().getOrDefault("AIBUILDER_LIVE_SIZE", "small"));
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
			String end = GameTestSupport.awaitChatAny(ctx, MAX_TICKS, SCREENSHOT_EVERY_TICKS,
					n -> ctx.takeScreenshot("aibuilder-live-progress-" + n), "Done: ", "Stopped", "Build failed", "Your Claude usage limit",
					"Claude's paid extra usage");
			AiBuilderMod.LOG.info("Live build ended: {}", end);
			AiBuilderMod.LOG.info("Live build chat: {}", GameTestSupport.chat());
			ctx.waitTicks(40);
			ctx.takeScreenshot("aibuilder-live-player-view");

			// Back and up, then from the front-left and the left side, each looking at the middle of the site, as far
			// off as the tier is big (a Small site's middle is 15 blocks ahead, a Medium's 27). As a spectator, so
			// nothing falls (the M5 castle's player did, from 32 up), and once the chat has faded.
			server.runCommand("gamemode spectator @a");
			ctx.waitTicks(200);
			int span = size == SizeTier.AUTO ? SizeTier.LARGE.size() : size.size();
			double middleZ = Tunables.SITE_GAP + span / 2.0 - 0.5;
			String middle = " facing 0.5 " + (feetY + span / 8) + " " + middleZ;
			server.runCommand("tp @a 0.5 " + (feetY + Math.round(span * 0.66)) + " " + (0.5 - span / 2) + middle);
			ctx.waitTicks(80);
			singleplayer.getConnection().waitForChunksRender();
			ctx.takeScreenshot("aibuilder-live-overview");
			server.runCommand("tp @a " + (0.5 + Math.round(span * 0.75)) + " " + (feetY + Math.round(span * 0.36)) + " " + (0.5 - span / 5) + middle);
			ctx.waitTicks(80);
			singleplayer.getConnection().waitForChunksRender();
			ctx.takeScreenshot("aibuilder-live-front-left");
			server.runCommand("tp @a " + (0.5 + Math.round(span * 1.1)) + " " + (feetY + span / 3) + " " + middleZ + middle);
			ctx.waitTicks(80);
			singleplayer.getConnection().waitForChunksRender();
			ctx.takeScreenshot("aibuilder-live-side");
		}
	}
}
