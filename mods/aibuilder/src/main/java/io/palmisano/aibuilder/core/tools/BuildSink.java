package io.palmisano.aibuilder.core.tools;

import java.util.List;

import io.palmisano.aibuilder.core.anim.MovingPart;
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

	/** The build has changed as many positions as its tier allows (spec §5.4); further new positions are dropped. */
	boolean capReached();

	/** Blocks queued and not yet placed. */
	long pending();

	/** A moving part's motion, as animate gave it (replacing any it had). */
	void animate(MovingPart part);

	void finish(String summary);
}
