package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.GameTestSupport.check;

import java.util.Comparator;
import java.util.Map;

import com.mojang.math.Transformation;
import io.palmisano.aibuilder.TestHooks;
import io.palmisano.aibuilder.mixin.DisplayAccessor;
import io.palmisano.aibuilder.world.AnimationStore;
import io.palmisano.aibuilder.world.Displays;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Display;
import org.joml.Vector3f;

/**
 * A moving build in the real game (spec v2 §4.7), with the fake Claude Code in place of the real one: a stone post with
 * a wing either side, flapping. Pictured with the wings down and half a flap later up (look at the screenshots), and
 * the right wing's tip measured: below where it was built at the start of a flap, above it half a flap on. Then undo
 * takes the wings away with the post.
 */
@SuppressWarnings("UnstableApiUsage")
public class MovingBuildClientGameTest implements FabricClientGameTest {
	/** The fake's flap: both wings swing every 40 ticks, down at the start of a flap and up half-way. */
	private static final int PERIOD = 40;

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
			TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "flap");
			GameTestSupport.runPlayerCommand(ctx, "aibuild a post with flapping wings");
			GameTestSupport.awaitChat(ctx, "A stone post with flapping wings.", 600);
			GameTestSupport.awaitChatStartingWith(ctx, "Done: ", 600);
			long start = server.computeOnServer(s -> AnimationStore.get(s).builds().getFirst().key().start());

			server.waitFor(s -> Math.floorMod(s.overworld().getGameTime() - start, PERIOD) == 0, 200);
			double down = server.computeOnServer(MovingBuildClientGameTest::rightTipRise);
			ctx.takeScreenshot("aibuilder-moving-wings-down");
			server.waitFor(s -> Math.floorMod(s.overworld().getGameTime() - start, PERIOD) == PERIOD / 2, 200);
			double up = server.computeOnServer(MovingBuildClientGameTest::rightTipRise);
			ctx.takeScreenshot("aibuilder-moving-wings-up");
			check(down < -1, "the right wing's tip at the start of a flap is " + down + " blocks from where it was built, not down");
			check(up > 1, "the right wing's tip half a flap on is " + up + " blocks from where it was built, not up");

			GameTestSupport.runPlayerCommand(ctx, "aibuild undo");
			GameTestSupport.awaitChatStartingWith(ctx, "Undone: ", 600);
			check(server.computeOnServer(s -> AnimationStore.get(s).builds().isEmpty()), "a moving build is left after the undo");
			long left = server.computeOnServer(s -> {
				int n = 0;
				for (var entity : s.overworld().getAllEntities()) {
					if (entity instanceof Display.BlockDisplay && entity.entityTags().contains(Displays.TAG) && !entity.isRemoved()) {
						n++;
					}
				}
				return (long) n;
			});
			check(left == 0, left + " of the build's displays are left after the undo");
		}
	}

	/** How far the centre of the right wing's outermost block is above (or below) where it was built, by its pose now. */
	private static double rightTipRise(MinecraftServer server) {
		AnimationStore.Part wing = AnimationStore.get(server).builds().getFirst().part("right_wing").orElseThrow();
		AnimationStore.PartDisplay tip = wing.displays().stream().max(Comparator.comparingInt(d -> d.block().x())).orElseThrow();
		Display.BlockDisplay display = (Display.BlockDisplay) server.overworld().getEntity(tip.id());
		Transformation pose = DisplayAccessor.aibuilder$transformation(display.getEntityData());
		Vector3f centre = pose.leftRotation().transform(new Vector3f(0.5f, 0.5f, 0.5f), new Vector3f()).add(pose.translation());
		return centre.y() - 0.5;
	}
}
