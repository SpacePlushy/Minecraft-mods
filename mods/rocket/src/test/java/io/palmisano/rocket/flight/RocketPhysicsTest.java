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
