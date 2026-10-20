package io.palmisano.drone.flight;

/**
 * Pilot input for one tick. {@code forward}, {@code strafe} (positive = right) and {@code vertical}
 * are clamped to [-1, 1]; {@code targetYaw} is the heading to turn toward, in degrees.
 */
public record DroneInput(double forward, double strafe, double vertical, double targetYaw) {

	public DroneInput {
		forward = clamp(forward);
		strafe = clamp(strafe);
		vertical = clamp(vertical);
	}

	/** No stick input; hold heading {@code yaw}. */
	public static DroneInput hover(double yaw) {
		return new DroneInput(0, 0, 0, yaw);
	}

	private static double clamp(double value) {
		return Math.max(-1, Math.min(1, value));
	}
}
