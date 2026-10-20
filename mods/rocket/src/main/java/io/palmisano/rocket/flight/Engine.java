package io.palmisano.rocket.flight;

/** Engine phase (spec §7 step 3). */
public enum Engine {
	OFF,
	/** On the pad, spooling up: vapor, no thrust. */
	IGNITION,
	/** Lit; thrust follows the throttle (which may be idling at 0). */
	BURNING
}
