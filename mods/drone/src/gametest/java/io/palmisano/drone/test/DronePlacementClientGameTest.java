package io.palmisano.drone.test;

import static io.palmisano.drone.test.GameTestSupport.check;

import io.palmisano.drone.entity.DroneEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.world.phys.AABB;

/** Placing the drone with its item, mounting it, and refusing to spawn without room. */
@SuppressWarnings("UnstableApiUsage")
public class DronePlacementClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		placeAndMount(ctx);
		noRoomNoDrone(ctx);
		showcase(ctx);
	}

	/** Close-up of three unridden drones facing +Z, -X and -Z, for checking the model by eye. */
	private static void showcase(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = GameTestSupport.flatArena(ctx)) {
			sp.getServer().runCommand("summon drone:drone -2 100 3.5 {Rotation:[0f,0f]}");
			sp.getServer().runCommand("summon drone:drone 0.5 100 3.5 {Rotation:[90f,0f]}");
			sp.getServer().runCommand("summon drone:drone 3 100 3.5 {Rotation:[180f,0f]}");
			ctx.runOnClient(mc -> {
				mc.player.setXRot(30f);
				if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			});
			ctx.waitTicks(10);
			System.out.println("Screenshot: " + ctx.takeScreenshot("drone-showcase"));
			ctx.runOnClient(mc -> mc.gui.hud.toggle());
		}
	}

	private static void placeAndMount(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = GameTestSupport.flatArena(ctx)) {
			sp.getServer().runCommand("give @a drone:drone");
			ctx.runOnClient(mc -> { // looks at floor block (0,99,2)
				mc.player.getInventory().setSelectedSlot(0);
				mc.player.setXRot(35f);
				mc.player.setYRot(0f);
			});
			ctx.waitTicks(2);
			ctx.getInput().pressKey(o -> o.keyUse);
			ctx.waitTicks(5);
			int count = GameTestSupport.serverDrones(sp, new AABB(-3, 99, -1, 3, 102, 6)).size();
			check(count == 1, "expected 1 drone, got " + count);

			sp.getServer().runCommand("ride @p mount @e[type=drone:drone,limit=1]");
			ctx.waitTicks(5);
			check(ctx.computeOnClient(mc -> mc.player.getVehicle() instanceof DroneEntity), "player not riding");

			ctx.runOnClient(mc -> {
				mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle(); // clean screenshots
				mc.player.setXRot(15f);
			});
			ctx.waitTicks(5);
			System.out.println("Screenshot: " + ctx.takeScreenshot("drone-parked"));
			// Side view from +X: drone front (+Z, red) should be on the screen's left.
			ctx.runOnClient(mc -> mc.player.setYRot(90f));
			ctx.waitTicks(5);
			System.out.println("Screenshot: " + ctx.takeScreenshot("drone-parked-side"));
			ctx.runOnClient(mc -> {
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			});
		}
	}

	/** Review Focus 4: a 1-wide corridor at x=6 has no room for a 1.5-wide drone. */
	private static void noRoomNoDrone(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = GameTestSupport.flatArena(ctx)) {
			sp.getServer().runCommand("fill 5 100 0 5 102 10 minecraft:stone");
			sp.getServer().runCommand("fill 7 100 0 7 102 10 minecraft:stone");
			sp.getServer().runCommand("tp @a 6.5 100 0.5 0 35"); // looks at floor block (6,99,2)
			sp.getServer().runCommand("give @a drone:drone");
			ctx.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(0));
			ctx.waitTicks(5);
			ctx.getInput().pressKey(o -> o.keyUse);
			ctx.waitTicks(5);
			check(GameTestSupport.serverDrones(sp, new AABB(4, 99, -1, 8, 103, 11)).isEmpty(),
					"drone spawned with no room");
		}
	}
}
