package io.palmisano.drone.flight;

/**
 * Every tunable flight constant. Angles in degrees, speeds in blocks/tick, responses per tick.
 * Change feel only through {@link #DEFAULT}; see spec §6.1.
 */
public record FlightTuning(
		double maxTilt,
		double tiltResponse,
		double drag,
		double topSpeed,
		double brakeGain,
		double maxClimb,
		double maxDescend,
		double verticalResponse,
		double yawResponse,
		double maxYawRate,
		double settleSpeed) {

	public static final FlightTuning DEFAULT = new FlightTuning(
			25, 0.20, 0.05, 0.40, 40, 0.25, 0.20, 0.15, 0.35, 10, 0.002);

	/** Horizontal acceleration at full tilt, chosen so steady-state speed at full tilt equals {@link #topSpeed}. */
	public double maxHorizontalAccel() {
		return topSpeed * drag / (1 - drag);
	}
}
