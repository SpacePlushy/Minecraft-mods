package io.palmisano.aibuilder.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.palmisano.aibuilder.core.agent.ClaudeLaunch;
import org.junit.jupiter.api.Test;

/** The waits a tool call can add up to, against Claude Code's MCP timeout (M4 review). */
class TunablesTest {
	private static final int MCP_SECONDS = ClaudeLaunch.MCP_TIMEOUT_MILLIS / 1000;

	@Test
	void everyWaitAnswersBeforeTheMcpTimeout() {
		// Each call may first wait for the build's start check.
		int ready = Tunables.READY_WAIT_SECONDS;
		assertTrue(ready + Tunables.CHECKPOINT_TIMEOUT_SECONDS < MCP_SECONDS, "checkpoint");
		assertTrue(ready + Tunables.SURVEY_TIMEOUT_SECONDS < MCP_SECONDS, "survey_area");
		// terraform waits for what's queued, then reads the ground again.
		assertTrue(ready + Tunables.TERRAFORM_PLACE_WAIT_SECONDS + Tunables.SURVEY_TIMEOUT_SECONDS < MCP_SECONDS, "terraform");
	}
}
