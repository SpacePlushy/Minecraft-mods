package io.palmisano.aibuilder.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The build's status panel, second by second (spec §5.3): for the owner while they're alive and in the build's world. */
class HudUpdateTest {
	/** Dead, or in another world: the panel comes down once, and then there's nothing to send. */
	@Test
	void deadOrElsewhereTakesItDown() {
		assertEquals(HudUpdate.CLEAR, HudUpdate.decide(false, true, true));
		assertEquals(HudUpdate.CLEAR, HudUpdate.decide(true, false, true));
		assertEquals(HudUpdate.NOTHING, HudUpdate.decide(false, true, false));
		assertEquals(HudUpdate.NOTHING, HudUpdate.decide(true, false, false));
	}

	/** Alive in the build's world: the status goes out, whether the panel was up or had been taken down. */
	@Test
	void backAgainPutsItUp() {
		assertEquals(HudUpdate.SEND, HudUpdate.decide(true, true, false));
		assertEquals(HudUpdate.SEND, HudUpdate.decide(true, true, true));
	}
}
