package io.palmisano.drone.flight;

/**
 * DJI-style quadcopter physics, one tick at a time (spec §6). Pure Java: no Minecraft types.
 * Yaw 0 faces +Z; forward = (-sin yaw, cos yaw), right = (-cos yaw, -sin yaw) in (x, z).
 */
public final class FlightModel {
	// Observer-side filtering for observe(); not flight feel, so not in FlightTuning.
	private static final double OBSERVED_SMOOTHING = 0.5;
	private static final double LEVEL_OUT = 0.8;
	private static final double TELEPORT_SPEEDS = 4; // a one-tick move longer than 4 × topSpeed isn't flight

	private final FlightTuning t;

	public FlightModel(FlightTuning tuning) {
		this.t = tuning;
	}

	public FlightState step(FlightState s, DroneInput in) {
		// 1. Yaw eases toward the target, the short way round, capped per tick.
		double yaw = s.yaw() + clamp(wrapDegrees(in.targetYaw() - s.yaw()) * t.yawResponse(), t.maxYawRate());

		double rad = Math.toRadians(yaw);
		double fx = -Math.sin(rad), fz = Math.cos(rad);
		double rx = -Math.cos(rad), rz = -Math.sin(rad);
		double vx = s.vx(), vz = s.vz();

		// 2. Target tilt: stick input, or an active brake lean against local velocity.
		double targetPitch = in.forward() != 0 ? in.forward() * t.maxTilt()
				: clamp(-(vx * fx + vz * fz) * t.brakeGain(), t.maxTilt());
		double targetRoll = in.strafe() != 0 ? in.strafe() * t.maxTilt()
				: clamp(-(vx * rx + vz * rz) * t.brakeGain(), t.maxTilt());

		// 3. Tilt lags toward its target.
		double pitch = s.pitchTilt() + (targetPitch - s.pitchTilt()) * t.tiltResponse();
		double roll = s.rollTilt() + (targetRoll - s.rollTilt()) * t.tiltResponse();

		// 4. Tilt becomes horizontal thrust in the drone's frame.
		double forwardAccel = thrust(pitch);
		double rightAccel = thrust(roll);
		vx += forwardAccel * fx + rightAccel * rx;
		vz += forwardAccel * fz + rightAccel * rz;

		// 5. Altitude hold: vertical speed eases toward the commanded climb/sink rate (0 = hold).
		double targetVy = in.vertical() * (in.vertical() > 0 ? t.maxClimb() : t.maxDescend());
		double vy = s.vy() + (targetVy - s.vy()) * t.verticalResponse();

		// 6. Drag.
		vx *= 1 - t.drag();
		vz *= 1 - t.drag();

		// 7. Settle tiny residual drift to an exact hover.
		if (in.forward() == 0 && in.strafe() == 0 && Math.hypot(vx, vz) < t.settleSpeed()) {
			vx = 0.0;
			vz = 0.0;
			pitch = 0.0;
			roll = 0.0;
		}
		if (in.vertical() == 0 && Math.abs(vy) < t.settleSpeed()) {
			vy = 0.0;
		}

		return new FlightState(vx, vy, vz, yaw, pitch, roll);
	}

	/**
	 * State for a drone this side doesn't simulate, given how far it moved since last tick (spec §5.1).
	 * Velocity follows the seen movement, smoothed so a tick without a vehicle-move packet doesn't read
	 * as a stop; whichever side takes over then brakes from real speed. A jump far beyond flying speed
	 * is a teleport and reads as rest. Tilt visibly levels out.
	 */
	public FlightState observe(FlightState s, double dx, double dy, double dz, double yaw) {
		if (Math.sqrt(dx * dx + dy * dy + dz * dz) > TELEPORT_SPEEDS * t.topSpeed()) {
			return new FlightState(0, 0, 0, yaw, s.pitchTilt() * LEVEL_OUT, s.rollTilt() * LEVEL_OUT);
		}
		return new FlightState(
				s.vx() + (dx - s.vx()) * OBSERVED_SMOOTHING,
				s.vy() + (dy - s.vy()) * OBSERVED_SMOOTHING,
				s.vz() + (dz - s.vz()) * OBSERVED_SMOOTHING,
				yaw, s.pitchTilt() * LEVEL_OUT, s.rollTilt() * LEVEL_OUT);
	}

	/** Bump and stop: zero the velocity on every axis where collision cut the intended movement. */
	public FlightState afterCollision(FlightState s, double intendedX, double intendedY, double intendedZ,
			double actualX, double actualY, double actualZ) {
		return new FlightState(
				blocked(intendedX, actualX) ? 0.0 : s.vx(),
				blocked(intendedY, actualY) ? 0.0 : s.vy(),
				blocked(intendedZ, actualZ) ? 0.0 : s.vz(),
				s.yaw(), s.pitchTilt(), s.rollTilt());
	}

	private static boolean blocked(double intended, double actual) {
		return Math.abs(actual - intended) > 1e-7;
	}

	private double thrust(double tiltDegrees) {
		return t.maxHorizontalAccel() * Math.sin(Math.toRadians(tiltDegrees)) / Math.sin(Math.toRadians(t.maxTilt()));
	}

	private static double clamp(double value, double limit) {
		return Math.max(-limit, Math.min(limit, value));
	}

	/** Maps an angle to (-180, 180]. */
	private static double wrapDegrees(double degrees) {
		double d = degrees % 360;
		if (d <= -180) d += 360;
		if (d > 180) d -= 360;
		return d;
	}
}
