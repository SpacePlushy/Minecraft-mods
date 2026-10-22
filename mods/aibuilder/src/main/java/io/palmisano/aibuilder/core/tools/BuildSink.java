package io.palmisano.aibuilder.core.tools;

import java.util.List;

import io.palmisano.aibuilder.core.place.Placement;

/** Where the tools send a build: the placement engine in the game, a recorder in tests. */
public interface BuildSink {
	void brief(Brief brief);

	/** One batch per write call, in world positions. Thread-safe. */
	void queue(List<Placement> batch);

	/** Blocks until everything queued so far is placed; returns the blocks placed so far. */
	int checkpoint(String phase);

	/** Blocks until everything queued so far is placed (or the checkpoint timeout), without marking a phase. */
	void awaitPlaced();

	void finish(String summary);
}
