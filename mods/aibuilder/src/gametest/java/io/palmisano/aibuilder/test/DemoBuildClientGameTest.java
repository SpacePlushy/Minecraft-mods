package io.palmisano.aibuilder.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.world.UndoStore;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code /aibuild test} in a real singleplayer world, where the player owns the world: the demo house goes up in
 * front of them slowly enough to watch, a second build is turned away while it runs, undo takes it all back, and a
 * build cancelled half-way undoes too.
 */
@SuppressWarnings("UnstableApiUsage")
public class DemoBuildClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try {
			run(ctx);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void run(ClientGameTestContext ctx) throws IOException {
		try (TestSingleplayerContext singleplayer = GameTestSupport.world(ctx)) {
			TestServerContext server = singleplayer.getServer();
			// Face south on the flat test world. The frame's origin is SITE_GAP ahead of the feet, on the grass.
			faceSouthAtTheStart(ctx, server, "~");
			int feetY = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getBlockY());
			UUID player = server.computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getUUID());
			// The door is at local (0, 1, 2): the origin is one below the feet, SITE_GAP south of them.
			BlockPos door = new BlockPos(0, feetY, Tunables.SITE_GAP + 2);
			BlockPos underDoor = door.below();

			// What Claude would see of this spot: the summary in chat, the full text saved beside the game.
			GameTestSupport.runPlayerCommand(ctx, "aibuild survey");
			GameTestSupport.awaitChatStartingWith(ctx, "Survey: ", 200);
			GameTestSupport.awaitChatStartingWith(ctx, "Full survey (", 200);
			Path surveys = server.computeOnServer(s -> s.getServerDirectory().resolve("aibuilder/surveys"));
			GameTestSupport.check(Files.isDirectory(surveys) && Files.list(surveys).findAny().isPresent(), "no survey saved in " + surveys);

			GameTestSupport.runPlayerCommand(ctx, "aibuild test");
			GameTestSupport.runPlayerCommand(ctx, "aibuild test");
			GameTestSupport.awaitChatStartingWith(ctx, "Designing: Demo house");
			GameTestSupport.awaitChat(ctx, "A build is already running: /aibuild cancel stops it.");
			// A second in it's still going, and already in the undo history, which is saved with the world.
			ctx.waitTicks(20);
			GameTestSupport.runPlayerCommand(ctx, "aibuild status");
			GameTestSupport.awaitChatStartingWith(ctx, "Building ");
			GameTestSupport.check(server.computeOnServer(s -> UndoStore.get(s).peek(player).isPresent()),
					"the running build isn't in the undo history");
			GameTestSupport.awaitChatStartingWith(ctx, "Done: ", 600);
			// The finish summary goes to chat (spec §8).
			GameTestSupport.awaitChat(ctx, "A small oak house on a stone-brick footing, with a spruce gable roof, a path to the door and a poppy either"
					+ " side.");
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

			// Cancel half-way, once the foundation has started: what's placed stays, and undo takes it back.
			faceSouthAtTheStart(ctx, server, String.valueOf(feetY));
			GameTestSupport.runPlayerCommand(ctx, "aibuild test");
			server.waitFor(s -> s.overworld().getBlockState(underDoor).is(Blocks.STONE_BRICKS));
			GameTestSupport.runPlayerCommand(ctx, "aibuild cancel");
			GameTestSupport.awaitChatStartingWith(ctx, "Stopped: ");
			GameTestSupport.check(server.computeOnServer(s -> s.overworld().getBlockState(door).isAir()),
					"the cancelled build got as far as the door");
			GameTestSupport.runPlayerCommand(ctx, "aibuild undo");
			GameTestSupport.awaitChatCount(ctx, "Undone: ", 2, 100);
			GameTestSupport.check(server.computeOnServer(s -> s.overworld().getBlockState(underDoor).is(Blocks.GRASS_BLOCK)),
					"undo after the cancel left the foundation");

			GameTestSupport.runPlayerCommand(ctx, "aibuild undo");
			GameTestSupport.awaitChat(ctx, "Nothing to undo.");
			GameTestSupport.runPlayerCommand(ctx, "aibuild cancel");
			GameTestSupport.awaitChat(ctx, "Nothing to cancel.");
		}
	}

	/** At (0.5, y, 0.5) looking south, and the client knows it. */
	private static void faceSouthAtTheStart(ClientGameTestContext ctx, TestServerContext server, String y) {
		server.runCommand("tp @a 0.5 " + y + " 0.5 0 0");
		ctx.waitFor(mc -> mc.player != null && mc.player.getYRot() == 0 && Math.abs(mc.player.getZ() - 0.5) < 0.01);
	}
}
