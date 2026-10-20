package io.palmisano.rocket.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RocketPhysicsTest {
	private static final RocketTuning T = RocketTuning.DEFAULT;
	private static final RocketPhysics M = new RocketPhysics(T);
	private static final RocketInput SPACE = new RocketInput(0, 0, true, 0);
	private static final RocketInput IDLE = RocketInput.idle(0);

	private static RocketState run(RocketState s, RocketInput in, boolean onGround, int ticks) {
		for (int i = 0; i < ticks; i++) {
			s = M.step(s, in, onGround);
		}
		return s;
	}

	private static RocketState burning(double vy, double throttle) {
		return new RocketState(0, vy, 0, 0, 0, 0, throttle, Engine.BURNING, 0);
	}

	@Test
	void defaultTuningMatchesSpec() { // pins spec §7.1; retuning edits only this test
		assertEquals(0.04, T.gravity());
		assertEquals(0.065, T.maxThrust());
		assertEquals(0.02, T.drag());
		assertEquals(0.10, T.throttleResponse());
		assertEquals(40, T.ignitionTicks());
		assertEquals(20, T.shutdownTicks());
		assertEquals(20, T.maxTilt());
		assertEquals(0.08, T.tiltResponse());
		assertEquals(0.20, T.yawResponse());
		assertEquals(5, T.maxYawRate());
		assertEquals(0.5, T.groundFriction());
		assertEquals(0.003, T.settleSpeed());
		assertEquals(8, T.vaporHeight());
	}

	@Test
	void restingWithNoInputStaysPut() {
		assertEquals(RocketState.resting(0), run(RocketState.resting(0), IDLE, true, 100));
	}

	@Test
	void ignitionLastsIgnitionTicksThenBurns() {
		RocketState s = RocketState.resting(0);
		for (int step = 1; step <= 40; step++) {
			s = M.step(s, SPACE, true);
			assertEquals(Engine.IGNITION, s.engine(), "step " + step);
			assertEquals(0, s.throttle(), "step " + step);
			assertTrue(s.vy() <= 0, "lifted during ignition at step " + step);
		}
		assertEquals(Engine.BURNING, M.step(s, SPACE, true).engine());
	}

	@Test
	void releasingDuringIgnitionAborts() {
		RocketState s = run(RocketState.resting(0), SPACE, true, 10);
		s = M.step(s, IDLE, true);
		assertEquals(Engine.OFF, s.engine());
		s = M.step(s, SPACE, true);
		assertEquals(Engine.IGNITION, s.engine());
		assertEquals(0, s.engineTicks());
	}

	@Test
	void launchClimbsToTerminal() { // spec §7.1 simulation: liftoff 12 ticks after the 40-tick ignition
		Pad pad = new Pad(RocketState.resting(0), 0).fly(M, SPACE, 100);
		assertTrue(pad.liftoffTick >= 52 && pad.liftoffTick <= 54, "liftoff at tick " + pad.liftoffTick);
		assertTrue(pad.y > 16 && pad.y < 19, "altitude at tick 100: " + pad.y); // simulated 17.6
		pad.fly(M, SPACE, 300);
		assertEquals(1.225, pad.state.vy(), 0.01); // (0.065 - 0.04) * 0.98 / 0.02
	}

	@Test
	void releaseCoastsUpThenFalls() {
		Pad pad = new Pad(burning(1.0, 1), 100);
		double peak = pad.y;
		int fallingAt = -1;
		for (int i = 1; i <= 200 && pad.state.vy() >= 0; i++) {
			pad.fly(M, IDLE, 1);
			peak = Math.max(peak, pad.y);
			if (pad.state.vy() < 0) fallingAt = i;
		}
		assertTrue(fallingAt > 0 && fallingAt <= 35, "falling only from tick " + fallingAt); // simulated 31
		assertTrue(peak - 100 > 15 && peak - 100 < 19, "coasted up " + (peak - 100)); // simulated 16.9
	}

	@Test
	void freeFallConvergesToGravityTerminal() {
		assertEquals(-1.96, run(RocketState.resting(0), IDLE, false, 600).vy(), 0.01); // 0.04 * 0.98 / 0.02
	}

	@Test
	void midAirRelightIsInstant() {
		assertEquals(Engine.BURNING, M.step(RocketState.resting(0), SPACE, false).engine());
	}

	@Test
	void landedIdleShutsDown() {
		RocketState s = run(burning(0, 0), IDLE, true, 19);
		assertEquals(Engine.BURNING, s.engine());
		assertEquals(Engine.OFF, M.step(s, IDLE, true).engine());
	}

	/** Maps an angle to (-180, 180]. */
	private static double wrap(double degrees) {
		double d = degrees % 360;
		if (d <= -180) d += 360;
		if (d > 180) d -= 360;
		return d;
	}

	private static RocketState observeSteady(RocketState s, double dz, int ticks) {
		for (int i = 0; i < ticks; i++) {
			s = M.observe(s, 0, 0, dz, 0);
		}
		return s;
	}

	@Test
	void yawIsRateLimited() {
		assertEquals(5, M.step(RocketState.resting(0), RocketInput.idle(170), true).yaw(), 1e-9);
	}

	@Test
	void yawTakesShortestPath() { // 350 -> 10 turns +20 * 0.2 = +4
		assertEquals(354, M.step(RocketState.resting(350), RocketInput.idle(10), true).yaw(), 1e-9);
	}

	@Test
	void yawHandlesUnwrappedPlayerYaw() { // Review Focus 3: after spinning the mouse, player yaw can be 725
		RocketState s = M.step(RocketState.resting(0), RocketInput.idle(725), true);
		assertEquals(1.0, wrap(s.yaw()), 1e-9);
		s = run(s, RocketInput.idle(725), true, 100);
		assertEquals(0, wrap(s.yaw() - 725), 0.01);
	}

	@Test
	void tiltFollowsKeysAndSelfRights() {
		RocketState s = run(RocketState.resting(0), new RocketInput(1, 0, false, 0), false, 100);
		assertEquals(20, s.pitchTilt(), 0.1);
		assertTrue(Math.abs(run(s, IDLE, false, 100).pitchTilt()) < 0.1);
		s = run(RocketState.resting(0), new RocketInput(0, 1, false, 0), false, 100);
		assertEquals(20, s.rollTilt(), 0.1);
		assertTrue(Math.abs(run(s, IDLE, false, 100).rollTilt()) < 0.1);
	}

	@Test
	void noTiltOnTheGround() {
		RocketState s = run(RocketState.resting(0), new RocketInput(1, 1, true, 0), true, 20);
		assertEquals(Engine.IGNITION, s.engine());
		assertEquals(0, s.pitchTilt());
		assertEquals(0, s.rollTilt());
	}

	@Test
	void tiltedThrustPushesAlongHeading() {
		RocketState s = run(burning(0, 1), new RocketInput(1, 0, true, 0), false, 200);
		assertTrue(s.vz() > 0.5, "vz=" + s.vz()); // yaw 0 faces +Z
		assertTrue(Math.abs(s.vx()) < 1e-9, "vx=" + s.vx());
		s = run(burning(0, 1).withYaw(90), new RocketInput(1, 0, true, 90), false, 200);
		assertTrue(s.vx() < -0.5, "vx=" + s.vx()); // yaw 90 faces -X
	}

	@Test
	void groundFrictionStopsSliding() {
		RocketState sliding = new RocketState(0.5, 0, 0, 0, 0, 0, 0, Engine.OFF, 0);
		assertEquals(0.0, run(sliding, IDLE, true, 20).vx());
	}

	@Test
	void landingStopsTheFall() {
		Pad pad = new Pad(RocketState.resting(0), 20).fly(M, IDLE, 200);
		assertEquals(0, pad.y);
		assertEquals(0, pad.state.vy());
	}

	@Test
	void afterCollisionZeroesOnlyBlockedAxes() { // a wall stops x; the climb and the drift carry on
		RocketState s = new RocketState(0.3, 0.1, 0.2, 0, 10, 0, 1, Engine.BURNING, 0);
		RocketState r = M.afterCollision(s, 0.3, 0.1, 0.2, 0.0, 0.1, 0.2);
		assertEquals(0, r.vx());
		assertEquals(0.1, r.vy());
		assertEquals(0.2, r.vz());
		assertEquals(10, r.pitchTilt());
	}

	@Test
	void observeTracksSeenMovement() { // the side that takes over later starts from real speed
		RocketState s = observeSteady(RocketState.resting(0), 0.3, 20);
		assertEquals(0.3, s.vz(), 1e-4);
	}

	@Test
	void observeRidesOutAMissedPacket() { // a tick with no vehicle-move packet must not read as a stop
		RocketState s = M.observe(observeSteady(RocketState.resting(0), 0.3, 20), 0, 0, 0, 0);
		assertTrue(s.vz() > 0.1, "vz=" + s.vz());
	}

	@Test
	void observeTreatsTeleportAsRest() { // /tp of a ridden rocket must not fling it on hand-over
		RocketState s = M.observe(observeSteady(RocketState.resting(0), 0.3, 20), 0, 0, 50, 0);
		assertEquals(0, s.vz());
	}

	@Test
	void observeMeansEngineOff() { // only the simulating side knows the engine; the other side shows it off
		RocketState s = M.observe(new RocketState(0, 0, 0, 0, 10, -10, 0.8, Engine.BURNING, 3), 0, 0, 0, 0);
		assertEquals(Engine.OFF, s.engine());
		assertEquals(0, s.throttle());
		assertEquals(0, s.engineTicks());
		assertTrue(s.pitchTilt() < 10 && s.pitchTilt() > 0 && s.rollTilt() > -10 && s.rollTilt() < 0, "tilt " + s);
	}

	@Test
	void noseIsUnitAndFollowsTilt() {
		Vec up = RocketPhysics.nose(0, 0, 0);
		assertEquals(0, up.x(), 1e-12);
		assertEquals(1, up.y(), 1e-12);
		assertEquals(0, up.z(), 1e-12);
		Vec forward = RocketPhysics.nose(0, 20, 0); // yaw 0 faces +Z
		assertEquals(0.342, forward.z(), 1e-3);
		assertEquals(0, forward.x(), 1e-12);
		assertEquals(1, Math.sqrt(forward.x() * forward.x() + forward.y() * forward.y() + forward.z() * forward.z()), 1e-12);
		assertEquals(-0.342, RocketPhysics.nose(90, 20, 0).x(), 1e-3); // yaw 90 faces -X
	}
}
