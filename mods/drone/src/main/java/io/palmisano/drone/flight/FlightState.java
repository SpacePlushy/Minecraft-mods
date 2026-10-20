package io.palmisano.drone.flight;

/**
 * Drone physics state. Velocity in blocks/tick (world frame); yaw and tilts in degrees.
 * {@code pitchTilt > 0} is nose down, {@code rollTilt > 0} is right side down.
 */
public record FlightState(double vx, double vy, double vz, double yaw, double pitchTilt, double rollTilt) {

	public static FlightState hovering(double yaw) {
		return new FlightState(0, 0, 0, yaw, 0, 0);
	}

	public FlightState withYaw(double yaw) {
		return new FlightState(vx, vy, vz, yaw, pitchTilt, rollTilt);
	}
}
