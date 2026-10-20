package io.palmisano.drone.flight;

/**
 * DJI-style quadcopter physics, one tick at a time (spec §6). Pure Java: no Minecraft types.
 * Yaw 0 faces +Z; forward = (-sin yaw, cos yaw), right = (-cos yaw, -sin yaw) in (x, z).
 */
public final class FlightModel {
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

		// 5. Vertical: unchanged for now.
		double vy = s.vy();

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

		return new FlightState(vx, vy, vz, yaw, pitch, roll);
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
