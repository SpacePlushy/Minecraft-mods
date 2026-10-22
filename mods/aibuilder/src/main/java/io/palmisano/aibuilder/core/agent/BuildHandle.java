package io.palmisano.aibuilder.core.agent;

import java.util.concurrent.CompletableFuture;

public interface BuildHandle {
	/** Stops before the next tool call. */
	void cancel();

	CompletableFuture<BuildOutcome> outcome();

	/**
	 * Why the build ended, once {@link #outcome()} is FAILED, CAPPED or LIMITED; "" otherwise. For FAILED and LIMITED
	 * it's the player's whole line, shown as it is; for CAPPED, which limit ("the 150 tool-call limit").
	 */
	String reason();

	/** The Claude Code session the build ran in, as its start reported it (a refinement resumes it); "" for none. */
	default String sessionId() {
		return "";
	}
}
