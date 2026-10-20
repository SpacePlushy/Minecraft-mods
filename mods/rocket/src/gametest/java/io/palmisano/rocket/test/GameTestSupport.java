package io.palmisano.rocket.test;

import java.util.List;

import io.palmisano.rocket.entity.RocketEntity;
import io.palmisano.rocket.registry.ModItems;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Shared setup and assertions for the rocket client gametests. */
@SuppressWarnings("UnstableApiUsage")
final class GameTestSupport {
	/** The arena and the sky above it, up to the build limit. */
	static final AABB ARENA = new AABB(-10, 95, -10, 10, 320, 30);

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

	/** After {@code give}, the item reaches the client a variable number of ticks later; Use before that does nothing. */
	static void waitForRocketInHand(ClientGameTestContext ctx) {
		ctx.waitFor(mc -> mc.player.getMainHandItem().is(ModItems.ROCKET), 40);
	}

	/** Server-side rockets in {@code box}; use only for counting. */
	static List<RocketEntity> serverRockets(TestSingleplayerContext sp, AABB box) {
		return sp.getServer().computeOnServer(s -> s.overworld().getEntitiesOfClass(RocketEntity.class, box));
	}

	/** Position of the single rocket in the arena, read on the server thread. */
	static Vec3 serverRocketPos(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(s -> {
			List<RocketEntity> rockets = s.overworld().getEntitiesOfClass(RocketEntity.class, ARENA);
			check(rockets.size() == 1, "expected 1 rocket in arena, got " + rockets.size());
			return rockets.getFirst().position();
		});
	}

	static void check(boolean ok, String msg) {
		if (!ok) {
			throw new AssertionError(msg);
		}
	}
}
