package io.palmisano.drone.test;

import static io.palmisano.drone.test.GameTestSupport.check;
import static io.palmisano.drone.test.GameTestSupport.serverDronePos;

import io.palmisano.drone.entity.DroneEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.world.phys.Vec3;

/** Flying the drone with the rider's real key mappings. */
@SuppressWarnings("UnstableApiUsage")
public class DroneFlightClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		flyForward(ctx);
		climbAndHold(ctx);
		wallStopsDrone(ctx);
		dismountMidAirHovers(ctx);
	}

	/** Arena with a mid-air drone at (0, 101, 4) facing +Z and the player riding it. */
	private static TestSingleplayerContext mountedArena(ClientGameTestContext ctx) {
		TestSingleplayerContext sp = GameTestSupport.flatArena(ctx);
		sp.getServer().runCommand("summon drone:drone 0 101 4");
		sp.getServer().runCommand("ride @p mount @e[type=drone:drone,limit=1]");
		ctx.waitTicks(5);
		ctx.runOnClient(mc -> mc.player.setYRot(0f));
		check(ctx.computeOnClient(mc -> mc.player.getVehicle() instanceof DroneEntity), "player not riding");
		return sp;
	}

	private static float clientPitchTilt(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> mc.player.getVehicle() instanceof DroneEntity d ? d.getPitchTilt(1f) : Float.NaN);
	}

	/** The client's copy of the single drone in the arena, ridden or not. */
	private static Vec3 clientDronePos(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> mc.level.getEntitiesOfClass(DroneEntity.class, GameTestSupport.ARENA).getFirst().position());
	}

	private static void flyForward(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = mountedArena(ctx)) {
			double z0 = serverDronePos(sp).z;
			ctx.getInput().holdKey(o -> o.keyUp);
			ctx.waitTicks(30);
			float tilt = clientPitchTilt(ctx);
			check(tilt > 10, "no forward tilt: " + tilt);

			ctx.runOnClient(mc -> {
				mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
				if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
				mc.player.setXRot(15f);
			});
			System.out.println("Screenshot: " + ctx.takeScreenshot("drone-flying"));
			ctx.runOnClient(mc -> mc.player.setYRot(90f)); // side view; rider yaw steers the drone, so only for one frame
			System.out.println("Screenshot: " + ctx.takeScreenshot("drone-flying-side"));
			ctx.runOnClient(mc -> {
				mc.player.setYRot(0f);
				mc.options.setCameraType(CameraType.FIRST_PERSON);
				mc.gui.hud.toggle();
			});

			ctx.waitTicks(30);
			ctx.getInput().releaseKey(o -> o.keyUp);
			ctx.waitTicks(5);
			Vec3 p = serverDronePos(sp);
			check(p.z - z0 > 5, "moved only " + (p.z - z0));
			check(Math.abs(p.x) < 0.5, "drifted sideways to x=" + p.x);
		}
	}

	private static void climbAndHold(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = mountedArena(ctx)) {
			double y0 = serverDronePos(sp).y;
			ctx.getInput().holdKey(o -> o.keyJump);
			ctx.waitTicks(40);
			ctx.getInput().releaseKey(o -> o.keyJump);
			ctx.waitTicks(2);
			double y1 = serverDronePos(sp).y;
			check(y1 - y0 > 4, "climbed only " + (y1 - y0));
			ctx.waitTicks(28);
			double y2 = serverDronePos(sp).y;
			ctx.waitTicks(20);
			double y3 = serverDronePos(sp).y;
			check(Math.abs(y3 - y2) < 0.05, "not holding altitude: " + y2 + " -> " + y3);
		}
	}

	/** Review Focus 2: flying into a wall stops the drone there, and it can still slide sideways. */
	private static void wallStopsDrone(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = mountedArena(ctx)) {
			sp.getServer().runCommand("fill -5 100 12 5 106 12 minecraft:stone");
			ctx.getInput().holdKey(o -> o.keyUp);
			ctx.waitTicks(100);
			Vec3 p = serverDronePos(sp);
			check(p.z < 12 - 0.75 + 0.01, "passed the wall: z=" + p.z);
			double pushZ = ctx.computeOnClient(mc -> mc.player.getVehicle().getDeltaMovement().z);
			check(pushZ == 0, "still pushing into wall: vz=" + pushZ);

			ctx.getInput().holdKey(o -> o.keyRight);
			ctx.waitTicks(20);
			ctx.getInput().releaseKey(o -> o.keyRight);
			ctx.getInput().releaseKey(o -> o.keyUp);
			ctx.waitTicks(2);
			double dx = serverDronePos(sp).x - p.x;
			check(dx < -1, "could not slide right along the wall: dx=" + dx); // right is -X when facing +Z
		}
	}

	/**
	 * Review Focus 1: hopping off mid-flight, the drone keeps its momentum and brakes like a released
	 * stick (spec §6.1: drifts a few blocks), then hovers in place and levels out.
	 */
	private static void dismountMidAirHovers(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = mountedArena(ctx)) {
			ctx.getInput().holdKey(o -> o.keyUp);
			ctx.waitTicks(30); // ~0.29 blocks/tick
			// Dismount is decided server-side from the shift state in the input packet, so hold it like a player would.
			ctx.getInput().holdKey(o -> o.keyShift);
			ctx.waitFor(mc -> mc.player.getVehicle() == null, 10);
			ctx.getInput().releaseKey(o -> o.keyShift);
			ctx.getInput().releaseKey(o -> o.keyUp);
			double zOff = serverDronePos(sp).z;

			// The drone you just left glides between server updates (interpolates like a boat) instead of jumping.
			double maxStep = 0;
			Vec3 prev = clientDronePos(ctx);
			for (int i = 0; i < 12; i++) {
				ctx.waitTick();
				Vec3 cur = clientDronePos(ctx);
				maxStep = Math.max(maxStep, cur.distanceTo(prev));
				prev = cur;
			}
			check(maxStep < 0.5, "drone view jumped " + maxStep + " blocks in one tick"); // flying speed is < 0.3

			ctx.waitTicks(80);
			Vec3 p1 = serverDronePos(sp);
			check(p1.z - zOff > 1, "stopped dead instead of braking: coasted " + (p1.z - zOff)); // simulated ~2.5
			ctx.waitTicks(20);
			Vec3 p2 = serverDronePos(sp);
			check(p1.distanceTo(p2) < 0.01, "drone drifting after dismount: " + p1 + " -> " + p2);
			float tilt = ctx.computeOnClient(mc -> mc.level.getEntitiesOfClass(DroneEntity.class, GameTestSupport.ARENA)
					.getFirst().getPitchTilt(1f));
			check(Math.abs(tilt) < 0.5, "tilt did not level out: " + tilt);
		}
	}
}
