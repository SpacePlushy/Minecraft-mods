package io.palmisano.rocket.flight;

/**
 * Every tunable flight constant. Speeds in blocks/tick, accelerations in blocks/tick², angles in
 * degrees, responses per tick. Change feel only through {@link #DEFAULT}; see spec §7.1.
 */
public record RocketTuning(
		double gravity,
		double maxThrust,
		double drag,
		double throttleResponse,
		int ignitionTicks,
		int shutdownTicks,
		double maxTilt,
		double tiltResponse,
		double yawResponse,
		double maxYawRate,
		double groundFriction,
		double settleSpeed,
		double vaporHeight) {

	public static final RocketTuning DEFAULT = new RocketTuning(
			0.04, 0.065, 0.02, 0.10, 40, 20, 20, 0.08, 0.20, 5, 0.5, 0.003, 8);
}
