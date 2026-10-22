package io.palmisano.aibuilder.test;

import io.palmisano.aibuilder.core.Tunables;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code /aibuild test} in a real singleplayer world, where the player owns the world: the demo house goes up in
 * front of them, a second build is turned away while it runs, and undo takes it all back.
 */
@SuppressWarnings("UnstableApiUsage")
public class DemoBuildClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext singleplayer = GameTestSupport.world(ctx)) {
			TestServerContext server = singleplayer.getServer();
			// Face south on the flat test world. The frame's origin is SITE_GAP ahead of the feet, on the grass.
			server.runCommand("tp @a 0.5 ~ 0.5 0 0");
			ctx.waitFor(mc -> mc.player != null && mc.player.getYRot() == 0 && Math.abs(mc.player.getZ() - 0.5) < 0.01);
			int feetY = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getBlockY());
			// The door is at local (0, 1, 2): the origin is one below the feet, SITE_GAP south of them.
			BlockPos door = new BlockPos(0, feetY, Tunables.SITE_GAP + 2);

			GameTestSupport.runPlayerCommand(ctx, "aibuild test");
			GameTestSupport.runPlayerCommand(ctx, "aibuild test");
			GameTestSupport.awaitChatStartingWith(ctx, "Designing: Demo house");
			GameTestSupport.awaitChat(ctx, "A build is already running: /aibuild cancel stops it.");
			GameTestSupport.awaitChatStartingWith(ctx, "Done: ");
			GameTestSupport.check(server.computeOnServer(s -> s.overworld().getBlockState(door).is(Blocks.OAK_DOOR)), "no door at " + door);

			// Step back to see the whole house.
			server.runCommand("tp @a 0.5 " + feetY + " -12.5 0 -10");
			ctx.waitFor(mc -> mc.player != null && mc.player.getZ() < -12);
			singleplayer.getConnection().waitForChunksRender();
			ctx.takeScreenshot("aibuilder-demo-house");

			GameTestSupport.runPlayerCommand(ctx, "aibuild undo");
			GameTestSupport.awaitChatStartingWith(ctx, "Undone: ");
			GameTestSupport.check(server.computeOnServer(s -> s.overworld().getBlockState(door).isAir()), "the door is still at " + door);
			ctx.waitTicks(5);
			singleplayer.getConnection().waitForChunksRender();
			ctx.takeScreenshot("aibuilder-demo-undone");

			GameTestSupport.runPlayerCommand(ctx, "aibuild undo");
			GameTestSupport.awaitChat(ctx, "Nothing to undo.");
			GameTestSupport.runPlayerCommand(ctx, "aibuild cancel");
			GameTestSupport.awaitChat(ctx, "Nothing to cancel.");
		}
	}
}
