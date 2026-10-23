package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.GameTestSupport.check;

import java.util.Map;
import java.util.UUID;

import io.palmisano.aibuilder.TestHooks;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.request.BuildSession;
import io.palmisano.aibuilder.world.Positions;
import io.palmisano.aibuilder.world.SessionStore;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A machine in the real game (spec v2 §5.4), with the fake Claude Code in place of the real one: a lever that opens a
 * piston door. When the build ends the door is closed (a torch holds its piston up); the lever flipped, it opens;
 * flipped back, it closes. Pictured both ways (look at the screenshots), then undone, leaving nothing behind.
 */
@SuppressWarnings("UnstableApiUsage")
public class MachineClientGameTest implements FabricClientGameTest {
	/** The fake's door: a cobblestone block in a stone wall's doorway, pulled down into the ground by a sticky piston. */
	private static final Pos DOORWAY = new Pos(0, 1, 5);
	private static final Pos LEVER = new Pos(2, 0, 5);
	private static final Pos PISTON = new Pos(0, -1, 5);

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
			TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "door");
			GameTestSupport.runPlayerCommand(ctx, "aibuild a lever that opens a piston door");
			GameTestSupport.awaitChat(ctx, "A piston door on a lever.", 600);
			GameTestSupport.awaitChatStartingWith(ctx, "Done: ", 600);
			LocalFrame frame = server.computeOnServer(s -> {
				BuildSession session = SessionStore.get(s).get(owner).orElseThrow();
				return new LocalFrame(session.origin(), session.facing());
			});

			server.waitFor(s -> block(s, frame, DOORWAY).is(Blocks.COBBLESTONE), 200);
			ctx.waitTicks(10);
			ctx.takeScreenshot("aibuilder-machine-door-closed");
			server.runOnServer(s -> pull(s, frame));
			server.waitFor(s -> block(s, frame, DOORWAY).isAir(), 200);
			ctx.waitTicks(10);
			ctx.takeScreenshot("aibuilder-machine-door-open");
			server.runOnServer(s -> pull(s, frame));
			server.waitFor(s -> block(s, frame, DOORWAY).is(Blocks.COBBLESTONE), 200);

			GameTestSupport.runPlayerCommand(ctx, "aibuild undo");
			GameTestSupport.awaitChatStartingWith(ctx, "Undone: ", 600);
			check(server.computeOnServer(s -> block(s, frame, DOORWAY).isAir() && !block(s, frame, PISTON).is(Blocks.STICKY_PISTON)
					&& !block(s, frame, LEVER).is(Blocks.LEVER)), "the door is still there after the undo");
		}
	}

	private static BlockState block(MinecraftServer server, LocalFrame frame, Pos local) {
		return server.overworld().getBlockState(Positions.toBlockPos(frame.toWorld(local)));
	}

	/** Flips the door's lever, as a player's click does. */
	private static void pull(MinecraftServer server, LocalFrame frame) {
		BlockPos pos = Positions.toBlockPos(frame.toWorld(LEVER));
		((LeverBlock) Blocks.LEVER).pull(server.overworld().getBlockState(pos), server.overworld(), pos, null);
	}
}
