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

	/** The M5 live castle's checkpoints: one named two phases, one only their first words. */
	@Test
	void aCheckpointCoveringPhasesSkipsPastThem() {
		PhaseProgress progress = PhaseProgress.of(List.of("moat and foundation", "curtain walls", "corner towers",
				"gatehouse with drum towers and drawbridge", "keep with turrets and roof", "courtyard buildings", "interiors"));
		progress = progress.checkpointed("moat and foundation").checkpointed("curtain walls and corner towers");
		assertEquals("gatehouse with drum towers and drawbridge (4/7)", progress.label());
		progress = progress.checkpointed("gatehouse and keep");
		assertEquals("courtyard buildings (6/7)", progress.label());
	}

	/** Re-checking an earlier phase doesn't take the status back. */
	@Test
	void aCheckpointNeverGoesBack() {
		PhaseProgress progress = PhaseProgress.of(List.of("base", "walls", "roof", "garden")).checkpointed("roof");
		assertEquals("garden (4/4)", progress.label());
		assertEquals("garden (4/4)", progress.checkpointed("walls").label());
	}

	@Test
	void withoutPhasesTheLabelIsTheLastCheckpoint() {
		PhaseProgress progress = PhaseProgress.of(List.of());
		assertEquals("", progress.label());
		assertEquals("walls", progress.checkpointed("walls").label());
	}

	/** "and" twice must not make the checkpoint a bad argument (PR review). */
	@Test
	void aCheckpointMaySayAWordTwice() {
		PhaseProgress progress = PhaseProgress.of(List.of("walls", "roof", "towers"));
		assertEquals(2, progress.checkpointed("walls and roof and towers").current());
	}
}
