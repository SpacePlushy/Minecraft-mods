package io.palmisano.rocket.flight;

/**
 * Pilot input for one tick. {@code forward} and {@code strafe} (positive = right) are clamped to
 * [-1, 1]; {@code throttleUp} is Space held; {@code targetYaw} is the heading to turn toward, in degrees.
 */
public record RocketInput(double forward, double strafe, boolean throttleUp, double targetYaw) {

	public RocketInput {
		forward = clamp(forward);
		strafe = clamp(strafe);
	}

	/** No keys held; hold heading {@code yaw}. */
	public static RocketInput idle(double yaw) {
		return new RocketInput(0, 0, false, yaw);
	}

	private static double clamp(double value) {
		return Math.max(-1, Math.min(1, value));
	}
}
