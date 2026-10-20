package io.palmisano.drone.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Proves the game launches with the mod loaded and a world renders. */
@SuppressWarnings("UnstableApiUsage")
public class SmokeClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			System.out.println("Screenshot: " + context.takeScreenshot("drone-smoke"));
		}
	}
}
