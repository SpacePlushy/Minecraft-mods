package io.palmisano.rocket.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class EngineEventTest {
	@Test
	void eachTransitionHasItsEvent() {
		assertEquals(Optional.of(EngineEvent.IGNITION_STARTED), EngineEvent.between(Engine.OFF, Engine.IGNITION));
		assertEquals(Optional.of(EngineEvent.LIFTOFF), EngineEvent.between(Engine.IGNITION, Engine.BURNING));
		assertEquals(Optional.of(EngineEvent.LIFTOFF), EngineEvent.between(Engine.OFF, Engine.BURNING)); // mid-air relight
		assertEquals(Optional.of(EngineEvent.ABORTED), EngineEvent.between(Engine.IGNITION, Engine.OFF));
		assertEquals(Optional.of(EngineEvent.SHUTDOWN), EngineEvent.between(Engine.BURNING, Engine.OFF));
	}

	@Test
	void noChangeNoEvent() {
		for (Engine e : Engine.values()) {
			assertEquals(Optional.empty(), EngineEvent.between(e, e));
		}
	}
}
