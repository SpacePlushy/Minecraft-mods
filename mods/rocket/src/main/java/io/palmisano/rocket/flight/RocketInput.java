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

	/** Maps held keys to input; opposing keys cancel. */
	public static RocketInput fromKeys(boolean forward, boolean back, boolean left, boolean right, boolean up,
			double lookYaw) {
		return new RocketInput(axis(forward, back), axis(right, left), up, lookYaw);
	}

	private static double axis(boolean positive, boolean negative) {
		return (positive ? 1 : 0) - (negative ? 1 : 0);
	}

	private static double clamp(double value) {
		return Math.max(-1, Math.min(1, value));
	}
}
