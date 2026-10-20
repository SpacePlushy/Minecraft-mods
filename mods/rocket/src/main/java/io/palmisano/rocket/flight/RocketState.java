package io.palmisano.rocket.flight;

/**
 * Rocket physics state. Velocity in blocks/tick (world frame); yaw and tilts in degrees.
 * {@code pitchTilt > 0} tips the nose forward, {@code rollTilt > 0} tips it right. {@code engineTicks}
 * counts ticks since ignition started while in {@link Engine#IGNITION}, consecutive ticks landed at
 * idle while {@link Engine#BURNING}, and is 0 while {@link Engine#OFF}.
 */
public record RocketState(double vx, double vy, double vz, double yaw, double pitchTilt, double rollTilt,
		double throttle, Engine engine, int engineTicks) {

	public static RocketState resting(double yaw) {
		return new RocketState(0, 0, 0, yaw, 0, 0, 0, Engine.OFF, 0);
	}

	public RocketState withYaw(double yaw) {
		return new RocketState(vx, vy, vz, yaw, pitchTilt, rollTilt, throttle, engine, engineTicks);
	}
}
