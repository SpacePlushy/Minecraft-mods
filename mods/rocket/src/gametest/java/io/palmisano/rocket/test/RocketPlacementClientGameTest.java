package io.palmisano.rocket.test;

import static io.palmisano.rocket.test.GameTestSupport.check;
import static io.palmisano.rocket.test.GameTestSupport.serverRockets;

import io.palmisano.rocket.entity.RocketEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/** Placing the rocket with its item, climbing in, and refusing to spawn without room. */
@SuppressWarnings("UnstableApiUsage")
public class RocketPlacementClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		placeAndMount(ctx);
		noRoomNoRocket(ctx);
		showcase(ctx);
	}

	/** Gives a rocket, waits until a click would count, and right-clicks whatever the crosshair is on. */
	private static String useRocket(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runCommand("give @a rocket:rocket");
		ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
		GameTestSupport.waitForRocketInHand(ctx);
		String aim = ctx.computeOnClient(mc -> mc.hitResult instanceof BlockHitResult b ? b.getBlockPos().toShortString() : "nothing");
		ctx.getInput().pressKey(o -> o.keyUse);
		ctx.waitTicks(5);
		return aim;
	}

	private static void placeAndMount(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = GameTestSupport.flatArena(ctx)) {
			ctx.runOnClient(mc -> { // looks at floor block (0,99,2)
				mc.player.setXRot(35f);
				mc.player.setYRot(0f);
			});
			String aim = useRocket(ctx, sp);
			int count = serverRockets(sp, new AABB(-3, 99, -1, 3, 106, 8)).size();
			check(count == 1, "expected 1 rocket, got " + count + " (crosshair was on " + aim + ")");

			sp.getServer().runCommand("ride @p mount @e[type=rocket:rocket,limit=1]");
			ctx.waitTicks(5);
			check(ctx.computeOnClient(mc -> mc.player.getVehicle() instanceof RocketEntity), "player not riding");
			// The rider's eyes are inside the rocket's hitbox; their crosshair must pass through it, or a
			// creative left-click would remove the rocket from under them.
			ctx.runOnClient(mc -> mc.player.setXRot(60f));
			ctx.waitTicks(2);
			check(ctx.computeOnClient(mc -> !(mc.hitResult instanceof EntityHitResult e && e.getEntity() instanceof RocketEntity)),
					"rider's crosshair is stuck on their own rocket");

			ctx.runOnClient(mc -> {
				mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle(); // clean screenshots
				mc.player.setXRot(10f);
			});
			ctx.waitTicks(5);
			System.out.println("Screenshot: " + ctx.takeScreenshot("rocket-parked"));
			// Side view from +X, window (front, +Z) on the left. Rider yaw steers the rocket, so only for one frame.
			ctx.runOnClient(mc -> mc.player.setYRot(90f));
			System.out.println("Screenshot: " + ctx.takeScreenshot("rocket-parked-side"));
			ctx.runOnClient(mc -> {
				mc.player.setYRot(0f);
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				mc.player.setXRot(0f);
			});
			ctx.waitTicks(5);
			System.out.println("Screenshot: " + ctx.takeScreenshot("rocket-cockpit")); // the rider sees out of the cabin
			ctx.runOnClient(mc -> mc.gui.hud.toggle());
		}
	}

	/** Review Focus 4: no rocket in a 1-wide corridor or under a 3-block ceiling; each has a positive control. */
	private static void noRoomNoRocket(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = GameTestSupport.flatArena(ctx)) {
			sp.getServer().runCommand("fill 5 100 0 5 106 10 minecraft:stone");
			sp.getServer().runCommand("fill 7 100 0 7 106 10 minecraft:stone");
			sp.getServer().runCommand("tp @a 6.5 100 0.5 0 35"); // looks at floor block (6,99,2)
			GameTestSupport.waitUntilServerAcceptsClicks(ctx, sp, 6.5, 0.5);
			AABB corridor = new AABB(4, 99, -1, 9, 108, 11);
			String aim = useRocket(ctx, sp);
			check(serverRockets(sp, corridor).isEmpty(), "rocket spawned in a 1-wide corridor");
			sp.getServer().runCommand("fill 5 100 0 5 106 10 minecraft:air");
			sp.getServer().runCommand("fill 7 100 0 7 106 10 minecraft:air");
			ctx.waitTicks(2);
			useRocket(ctx, sp);
			check(serverRockets(sp, corridor).size() == 1, "control: no rocket even without walls (crosshair was on " + aim + ")");

			sp.getServer().runCommand("kill @e[type=rocket:rocket]");
			sp.getServer().runCommand("fill -3 103 3 3 103 8 minecraft:stone");
			sp.getServer().runCommand("tp @a 0.5 100 2.5 0 35"); // looks at floor block (0,99,4)
			GameTestSupport.waitUntilServerAcceptsClicks(ctx, sp, 0.5, 2.5);
			AABB underCeiling = new AABB(-3, 99, 2, 3, 108, 9);
			aim = useRocket(ctx, sp);
			check(serverRockets(sp, underCeiling).isEmpty(), "rocket spawned under a 3-block ceiling");
			sp.getServer().runCommand("fill -3 103 3 3 103 8 minecraft:air");
			ctx.waitTicks(2);
			useRocket(ctx, sp);
			check(serverRockets(sp, underCeiling).size() == 1, "control: no rocket even without the ceiling (crosshair was on " + aim + ")");
		}
	}

	/** Three unridden rockets facing +Z, -X and -Z, for checking the model by eye. */
	private static void showcase(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = GameTestSupport.flatArena(ctx)) {
			sp.getServer().runCommand("summon rocket:rocket -3 100 8 {Rotation:[0f,0f]}");
			sp.getServer().runCommand("summon rocket:rocket 0.5 100 8 {Rotation:[90f,0f]}");
			sp.getServer().runCommand("summon rocket:rocket 4 100 8 {Rotation:[180f,0f]}");
			ctx.runOnClient(mc -> {
				mc.player.setXRot(-8f);
				if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			});
			ctx.waitTicks(10);
			System.out.println("Screenshot: " + ctx.takeScreenshot("rocket-showcase"));
			ctx.runOnClient(mc -> mc.gui.hud.toggle());
		}
	}
}
