package io.palmisano.rocket.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Proves the game launches with the mod loaded and the test arena renders. */
@SuppressWarnings("UnstableApiUsage")
public class SmokeClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = GameTestSupport.flatArena(ctx)) {
			System.out.println("Screenshot: " + ctx.takeScreenshot("rocket-smoke"));
		}
	}
}
