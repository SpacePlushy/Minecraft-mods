package io.palmisano.rocket.flight;

/**
 * One particle to spawn this tick: an offset from the nozzle (the rocket's base center) and a
 * velocity, both in the world frame, in blocks and blocks/tick.
 */
public record Puff(Kind kind, double dx, double dy, double dz, double vx, double vy, double vz) {

	public enum Kind {
		/** White launch cloud rolling out along the ground. */
		VAPOR,
		/** Big slow smoke that lingers around the pad. */
		BILLOW,
		/** Fire out of the nozzle. */
		FLAME,
		/** Smoke trail behind the flame. */
		TRAIL
	}
}
