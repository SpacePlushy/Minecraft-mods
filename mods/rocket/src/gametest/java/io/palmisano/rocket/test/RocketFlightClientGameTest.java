package io.palmisano.rocket.test;

import static io.palmisano.rocket.test.GameTestSupport.check;
import static io.palmisano.rocket.test.GameTestSupport.serverRocketPos;

import io.palmisano.rocket.entity.RocketEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.world.phys.Vec3;

/** Launching and flying the rocket with the rider's real key mappings. Positions are read server-side. */
@SuppressWarnings("UnstableApiUsage")
public class RocketFlightClientGameTest implements FabricClientGameTest {
	private static final double PAD = 100;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		launchWaitsForIgnitionThenClimbs(ctx);
		abortDuringIgnition(ctx);
		steeringMovesForward(ctx);
		engineCutFallsAndLands(ctx);
		ceilingStopsLaunch(ctx);
		dismountMidAirFallsAndLands(ctx);
		hardLandingDoesNotHurtRider(ctx);
	}

	/** Arena with a rocket at (0.5, y, 4.5) facing +Z and the player in it. */
	private static TestSingleplayerContext mountedArena(ClientGameTestContext ctx, double y) {
		TestSingleplayerContext sp = GameTestSupport.flatArena(ctx);
		sp.getServer().runCommand("summon rocket:rocket 0.5 " + y + " 4.5");
		sp.getServer().runCommand("ride @p mount @e[type=rocket:rocket,limit=1]");
		ctx.waitTicks(5);
		ctx.runOnClient(mc -> mc.player.setYRot(0f));
		check(ctx.computeOnClient(mc -> mc.player.getVehicle() instanceof RocketEntity), "player not riding");
		return sp;
	}

	private static void waitForClientRocketAbove(ClientGameTestContext ctx, double y, int timeout) {
		ctx.waitFor(mc -> mc.player.getVehicle() instanceof RocketEntity r && r.getY() > y, timeout);
	}

	/** Waits until the server's rocket rests on the pad, then checks it stays there. */
	private static void waitForLandingOnPad(ClientGameTestContext ctx, TestSingleplayerContext sp, int timeout, String what) {
		for (int t = 0; Math.abs(serverRocketPos(sp).y - PAD) > 1e-6; t += 5) {
			check(t < timeout, what + ": still at y=" + serverRocketPos(sp).y + " after " + timeout + " ticks");
			ctx.waitTicks(5);
		}
		ctx.waitTicks(20);
		check(Math.abs(serverRocketPos(sp).y - PAD) < 1e-6, what + ": bounced off the pad to y=" + serverRocketPos(sp).y);
	}

	private static void thirdPerson(ClientGameTestContext ctx, float pitch) {
		ctx.runOnClient(mc -> {
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			mc.player.setXRot(pitch);
		});
	}

	private static void firstPerson(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
			if (mc.gui.hud.isHidden()) mc.gui.hud.toggle();
			mc.player.setXRot(0f);
		});
	}

	/** Spec §5/§7: holding Space on the pad gives 2 s of ignition (vapor, no lift), then liftoff and a climb. */
	private static void launchWaitsForIgnitionThenClimbs(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = mountedArena(ctx, PAD)) {
			thirdPerson(ctx, 40f);
			ctx.getInput().holdKey(o -> o.keyJump);
			ctx.waitTicks(30);
			double y = serverRocketPos(sp).y;
			check(Math.abs(y - PAD) < 1e-6, "lifted off during ignition: y=" + y);
			System.out.println("Screenshot: " + ctx.takeScreenshot("rocket-ignition"));
			ctx.waitTicks(60); // 90 ticks held: simulated ~11 blocks up
			y = serverRocketPos(sp).y;
			check(y > PAD + 3, "no liftoff after 90 ticks: y=" + y);
			System.out.println("Screenshot: " + ctx.takeScreenshot("rocket-flying"));
			ctx.waitTicks(70);
			y = serverRocketPos(sp).y;
			check(y > 120, "not climbing: y=" + y + " after 160 ticks");
			ctx.getInput().releaseKey(o -> o.keyJump);
			firstPerson(ctx);
		}
	}

	private static void abortDuringIgnition(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = mountedArena(ctx, PAD)) {
			ctx.getInput().holdKey(o -> o.keyJump);
			ctx.waitTicks(20);
			ctx.getInput().releaseKey(o -> o.keyJump);
			ctx.waitTicks(60);
			double y = serverRocketPos(sp).y;
			check(Math.abs(y - PAD) < 1e-6, "launched although Space was released during ignition: y=" + y);
		}
	}

	private static void steeringMovesForward(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = mountedArena(ctx, PAD)) {
			ctx.getInput().holdKey(o -> o.keyJump);
			waitForClientRocketAbove(ctx, PAD + 5, 200);
			Vec3 p0 = serverRocketPos(sp);
			ctx.getInput().holdKey(o -> o.keyUp);
			ctx.waitTicks(40);
			thirdPerson(ctx, 10f);
			ctx.runOnClient(mc -> mc.player.setYRot(90f)); // side view; rider yaw steers the rocket, so only for one frame
			System.out.println("Screenshot: " + ctx.takeScreenshot("rocket-tilted-side"));
			ctx.runOnClient(mc -> mc.player.setYRot(0f));
			Vec3 p1 = serverRocketPos(sp);
			ctx.getInput().releaseKey(o -> o.keyUp);
			ctx.getInput().releaseKey(o -> o.keyJump);
			firstPerson(ctx);
			check(p1.z - p0.z > 3, "W didn't push it forward: dz=" + (p1.z - p0.z));
			check(Math.abs(p1.x - p0.x) < 1, "drifted sideways: dx=" + (p1.x - p0.x));
		}
	}

	private static void engineCutFallsAndLands(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = mountedArena(ctx, PAD)) {
			ctx.getInput().holdKey(o -> o.keyJump);
			waitForClientRocketAbove(ctx, PAD + 15, 300);
			ctx.getInput().releaseKey(o -> o.keyJump);
			waitForLandingOnPad(ctx, sp, 400, "engine cut");
		}
	}

	/** Review Focus 2: a ceiling over the pad stops the climb, without passing through, until Space is released. */
	private static void ceilingStopsLaunch(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = mountedArena(ctx, PAD)) {
			sp.getServer().runCommand("fill -2 110 2 2 110 6 minecraft:stone");
			ctx.getInput().holdKey(o -> o.keyJump);
			for (int i = 0; i < 16; i++) {
				ctx.waitTicks(10);
				double top = serverRocketPos(sp).y + 4.625;
				check(top <= 110 + 1e-3, "rocket top at " + top + " passed the ceiling at 110");
			}
			check(serverRocketPos(sp).y > PAD + 4, "never reached the ceiling");
			ctx.getInput().releaseKey(o -> o.keyJump);
			waitForLandingOnPad(ctx, sp, 200, "after the ceiling");
		}
	}

	/** Review Focus 1: hopping out mid-flight with Space still held: the engine stops, the rocket falls and lands. */
	private static void dismountMidAirFallsAndLands(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = mountedArena(ctx, PAD)) {
			ctx.getInput().holdKey(o -> o.keyJump);
			waitForClientRocketAbove(ctx, PAD + 15, 300);
			// Dismount is decided server-side from the shift state in the input packet, so hold it like a player would.
			ctx.getInput().holdKey(o -> o.keyShift);
			ctx.waitFor(mc -> mc.player.getVehicle() == null, 20);
			ctx.getInput().releaseKey(o -> o.keyShift);
			waitForLandingOnPad(ctx, sp, 400, "after dismount");
			ctx.getInput().releaseKey(o -> o.keyJump);
		}
	}

	/** Review Focus 5: a 50-block fall with the engine off lands on the pad and doesn't hurt a survival rider. */
	private static void hardLandingDoesNotHurtRider(ClientGameTestContext ctx) {
		try (TestSingleplayerContext sp = mountedArena(ctx, 150)) {
			sp.getServer().runCommand("gamemode survival @a");
			waitForLandingOnPad(ctx, sp, 400, "hard landing");
			float health = sp.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getHealth());
			check(health == 20f, "rider hurt by the landing: health " + health);
			sp.getServer().runCommand("gamemode creative @a");
		}
	}
}
