package io.palmisano.drone.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FlightModelTest {
	private static final FlightTuning T = FlightTuning.DEFAULT;
	private static final FlightModel M = new FlightModel(T);

	private static FlightState run(FlightState s, DroneInput in, int ticks) {
		for (int i = 0; i < ticks; i++) {
			s = M.step(s, in);
		}
		return s;
	}

	/** Maps an angle to (-180, 180]. */
	private static double wrap(double degrees) {
		double d = degrees % 360;
		if (d <= -180) d += 360;
		if (d > 180) d -= 360;
		return d;
	}

	@Test
	void defaultTuningMatchesSpec() { // pins spec §6.1; retuning edits only this test
		assertEquals(25, T.maxTilt());
		assertEquals(0.20, T.tiltResponse());
		assertEquals(0.05, T.drag());
		assertEquals(0.40, T.topSpeed());
		assertEquals(40, T.brakeGain());
		assertEquals(10, T.maxYawRate());
		assertEquals(0.35, T.yawResponse());
		assertEquals(0.002, T.settleSpeed());
		assertEquals(0.25, T.maxClimb());
		assertEquals(0.20, T.maxDescend());
		assertEquals(0.15, T.verticalResponse());
		assertEquals(0.0211, T.maxHorizontalAccel(), 1e-4);
	}

	@Test
	void noInputFromRestStaysPut() {
		FlightState s = run(FlightState.hovering(0), DroneInput.hover(0), 100);
		assertEquals(FlightState.hovering(0), s);
	}

	@Test
	void fullForwardConvergesToTopSpeedAlongHeading() {
		FlightState s = run(FlightState.hovering(0), new DroneInput(1, 0, 0, 0), 200);
		assertEquals(T.maxTilt(), s.pitchTilt(), 0.01);
		assertEquals(T.topSpeed(), s.vz(), 0.01); // yaw 0 faces +Z
		assertEquals(0, s.vx(), 1e-9);
	}

	@Test
	void forwardFollowsYaw() { // yaw 90 -> forward is -X
		FlightState s = run(FlightState.hovering(90), new DroneInput(1, 0, 0, 90), 200);
		assertEquals(-T.topSpeed(), s.vx(), 0.01);
		assertEquals(0, s.vz(), 0.01);
	}

	@Test
	void topSpeedTakesMomentum() { // ~55% after 1 s (simulated 0.221)
		FlightState s = run(FlightState.hovering(0), new DroneInput(1, 0, 0, 0), 20);
		assertTrue(s.vz() > 0.18 && s.vz() < 0.26, "vz=" + s.vz());
	}

	@Test
	void strafeRightMovesAlongRightAxis() { // yaw 0 -> right is -X
		FlightState s = run(FlightState.hovering(0), new DroneInput(0, 1, 0, 0), 200);
		assertEquals(T.maxTilt(), s.rollTilt(), 0.01);
		assertEquals(-T.topSpeed(), s.vx(), 0.01);
	}

	@Test
	void releaseBrakesWithBackTiltThenSettles() {
		FlightState s = run(FlightState.hovering(0), new DroneInput(1, 0, 0, 0), 200);
		double minPitch = 0;
		for (int i = 0; i < 60; i++) {
			s = M.step(s, DroneInput.hover(0));
			minPitch = Math.min(minPitch, s.pitchTilt());
		}
		assertTrue(minPitch < -5, "brake lean " + minPitch); // simulated about -8.4 degrees
		assertEquals(FlightState.hovering(0), s); // simulated settle at tick 52
	}

	@Test
	void yawTurnIsRateLimited() {
		FlightState s = M.step(FlightState.hovering(0), DroneInput.hover(170));
		assertEquals(10, s.yaw(), 1e-9);
	}

	@Test
	void yawTakesShortestPath() { // 350 -> 10 turns +20 * 0.35 = +7
		FlightState s = M.step(FlightState.hovering(350), DroneInput.hover(10));
		assertEquals(7, wrap(s.yaw() - 350), 1e-9);
	}

	@Test
	void climbConvergesToMaxClimb() {
		assertEquals(T.maxClimb(), run(FlightState.hovering(0), new DroneInput(0, 0, 1, 0), 100).vy(), 0.005);
	}

	@Test
	void descendConvergesToMaxDescend() {
		assertEquals(-T.maxDescend(), run(FlightState.hovering(0), new DroneInput(0, 0, -1, 0), 100).vy(), 0.005);
	}

	@Test
	void releasingVerticalHoldsAltitude() {
		FlightState s = run(FlightState.hovering(0), new DroneInput(0, 0, 1, 0), 100);
		s = run(s, DroneInput.hover(0), 60);
		assertEquals(0, s.vy());
	}

	@Test
	void afterCollisionZeroesOnlyBlockedAxes() { // Review Focus 2
		FlightState s = new FlightState(0.3, 0.1, 0.2, 0, 10, 0);
		FlightState r = M.afterCollision(s, 0.3, 0.1, 0.2, 0.0, 0.1, 0.2);
		assertEquals(0, r.vx());
		assertEquals(0.1, r.vy());
		assertEquals(0.2, r.vz());
		assertEquals(10, r.pitchTilt()); // tilt untouched
	}

	@Test
	void afterCollisionStopsVerticalOnGround() {
		FlightState r = M.afterCollision(new FlightState(0, -0.2, 0, 0, 0, 0), 0, -0.2, 0, 0, 0, 0);
		assertEquals(0, r.vy());
	}

	@Test
	void yawHandlesUnwrappedPlayerYaw() { // Review Focus 3
		FlightState s = M.step(FlightState.hovering(0), DroneInput.hover(725));
		assertEquals(1.75, wrap(s.yaw()), 1e-9);
		s = run(s, DroneInput.hover(725), 50);
		assertEquals(0, wrap(s.yaw() - 725), 0.01);
	}
}
