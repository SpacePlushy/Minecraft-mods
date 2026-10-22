package io.palmisano.aibuilder.core.agent;

import java.util.concurrent.CompletableFuture;

public interface BuildHandle {
	/** Stops before the next tool call. */
	void cancel();

	CompletableFuture<BuildOutcome> outcome();

	/** Why the build ended, for the player, once {@link #outcome()} is FAILED, CAPPED or LIMITED; "" otherwise. */
	String reason();
}
