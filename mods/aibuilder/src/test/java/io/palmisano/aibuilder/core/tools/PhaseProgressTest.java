package io.palmisano.aibuilder.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The phase the status line names: checkpoints come at the end of each phase, so the next one is in progress. */
class PhaseProgressTest {
	@Test
	void eachCheckpointMovesToTheNextPhase() {
		PhaseProgress progress = PhaseProgress.of(List.of("foundation", "walls", "roof"));
		assertEquals("foundation (1/3)", progress.label());
		progress = progress.checkpointed("foundation");
		assertEquals("walls (2/3)", progress.label());
		// A name the brief didn't list still ends the phase in progress.
		progress = progress.checkpointed("walls are up");
		assertEquals("roof (3/3)", progress.label());
		progress = progress.checkpointed("roof");
		assertEquals("roof (3/3)", progress.label());
	}

	@Test
	void aCheckpointCanSkipAhead() {
		assertEquals("door (3/4)", PhaseProgress.of(List.of("base", "walls", "door", "roof")).checkpointed("walls").label());
	}

	@Test
	void withoutPhasesTheLabelIsTheLastCheckpoint() {
		PhaseProgress progress = PhaseProgress.of(List.of());
		assertEquals("", progress.label());
		assertEquals("walls", progress.checkpointed("walls").label());
	}
}
