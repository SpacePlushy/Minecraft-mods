package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.GameTestSupport.check;

import java.util.Map;
import java.util.UUID;

import io.palmisano.aibuilder.TestHooks;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.net.BuildPromptPayload;
import io.palmisano.aibuilder.world.SessionStore;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * The calculator in the real game (the M9 plan), with the fake Claude Code in place of the real one: "a working
 * calculator" at Epic size builds it from gadgets and wires; then 9 + 7 and 15 + 15 set on its levers, each read off its
 * digits and pictured (look at the screenshots), with a view of the whole of it.
 */
@SuppressWarnings("UnstableApiUsage")
public class CalculatorClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestSetup.live()) {
			return; // a live run is for LiveBuildClientGameTest alone
		}
		try (TestSingleplayerContext singleplayer = GameTestSupport.world(ctx)) {
			TestServerContext server = singleplayer.getServer();
			server.runCommand("time set noon");
			server.runCommand("gamerule advance_time false");
			server.runCommand("weather clear");
			server.runCommand("tp @a 0.5 ~ 0.5 0 0");
			ctx.waitFor(mc -> mc.player != null && mc.player.getYRot() == 0 && Math.abs(mc.player.getZ() - 0.5) < 0.01);
			UUID owner = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getUUID());
			TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "calculator");
			ctx.runOnClient(mc -> ClientPlayNetworking.send(new BuildPromptPayload("a working calculator", SizeTier.EPIC)));
			GameTestSupport.awaitChat(ctx, "A calculator: 9 + 7 shows 16 and 15 + 15 shows 30.", 6000);
			GameTestSupport.awaitChatStartingWith(ctx, "Done: ", 6000);
			FoundCalculator calculator = server.computeOnServer(s -> FoundCalculator.of(SessionStore.get(s).get(owner).orElseThrow()).orElseThrow());

			// As a spectator, so the camera stays where it's put (a player falls).
			server.runCommand("gamemode spectator @a");
			Pos[] view = calculator.view();
			Pos from = calculator.frame().toWorld(view[0]);
			Pos at = calculator.frame().toWorld(view[1]);
			server.runCommand("tp @a " + (from.x() + 0.5) + " " + from.y() + " " + (from.z() + 0.5) + " facing " + (at.x() + 0.5) + " " + (at.y() + 0.5)
					+ " " + (at.z() + 0.5));
			ctx.waitTicks(40);
			singleplayer.getConnection().waitForChunksRender();
			for (int[] sum : new int[][] {{9, 7}, {15, 15}}) {
				server.runOnServer(s -> calculator.set(s, sum[0], sum[1]));
				ctx.waitTicks(calculator.settle());
				String shown = server.computeOnServer(calculator::shows);
				ctx.takeScreenshot("aibuilder-calculator-" + sum[0] + "-plus-" + sum[1]);
				check(shown.equals(String.valueOf(sum[0] + sum[1])), sum[0] + " + " + sum[1] + " shows " + shown);
			}
			// The whole of it, from above its front-left.
			Pos over = calculator.frame().toWorld(new Pos(-60, 50, -20));
			Pos middle = calculator.frame().toWorld(new Pos(0, 0, 60));
			server.runCommand("tp @a " + over.x() + " " + over.y() + " " + over.z() + " facing " + middle.x() + " " + middle.y() + " " + middle.z());
			ctx.waitTicks(40);
			singleplayer.getConnection().waitForChunksRender();
			ctx.takeScreenshot("aibuilder-calculator-overview");
		}
	}
}
