package io.palmisano.rocket.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Shared setup and assertions for the rocket client gametests. */
@SuppressWarnings("UnstableApiUsage")
final class GameTestSupport {
	private GameTestSupport() {
	}

	/** A creative world with a stone floor at y=99 and clear air above; the player stands at (0.5, 100, 0.5) facing +Z. */
	static TestSingleplayerContext flatArena(ClientGameTestContext ctx) {
		TestSingleplayerContext sp = ctx.worldBuilder().create();
		sp.getServer().runCommand("gamemode creative @a");
		sp.getServer().runCommand("fill -10 99 -10 10 99 30 minecraft:stone");
		sp.getServer().runCommand("fill -10 100 -10 10 115 30 minecraft:air");
		sp.getServer().runCommand("tp @a 0.5 100 0.5 0 0");
		sp.getConnection().waitForChunksRender();
		waitUntilServerAcceptsClicks(ctx, sp, 0.5, 0.5);
		return sp;
	}

	/**
	 * The server silently ignores clicks until it has the client's "loaded" packet and its
	 * acknowledgement of the last teleport (ServerGamePacketListenerImpl.handleUseItemOn). Both arrive
	 * a variable number of ticks after the command, so wait for them instead of a fixed tick count.
	 */
	static void waitUntilServerAcceptsClicks(ClientGameTestContext ctx, TestSingleplayerContext sp, double x, double z) {
		ctx.waitFor(mc -> Math.abs(mc.player.getX() - x) < 1e-6 && Math.abs(mc.player.getZ() - z) < 1e-6, 40);
		ctx.waitTick(); // the client acknowledged the teleport when it applied it; the server reads that next tick
		for (int i = 0; !sp.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().connection.hasClientLoaded()); i++) {
			check(i < 60, "server never saw the client finish loading");
			ctx.waitTick();
		}
	}

	static void check(boolean ok, String msg) {
		if (!ok) {
			throw new AssertionError(msg);
		}
	}
}
