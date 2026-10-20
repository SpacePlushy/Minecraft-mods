package io.palmisano.rocket.flight;

import java.util.Optional;

/** Engine moments worth a sound (spec §8). */
public enum EngineEvent {
	IGNITION_STARTED,
	LIFTOFF,
	ABORTED,
	SHUTDOWN;

	/** The event for an engine change from one tick to the next, if any. */
	public static Optional<EngineEvent> between(Engine before, Engine after) {
		if (before == after) {
			return Optional.empty();
		}
		return Optional.ofNullable(switch (after) {
			case IGNITION -> IGNITION_STARTED;
			case BURNING -> LIFTOFF;
			case OFF -> before == Engine.IGNITION ? ABORTED : SHUTDOWN;
		});
	}
}
