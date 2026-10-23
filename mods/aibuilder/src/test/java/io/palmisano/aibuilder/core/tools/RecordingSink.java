package io.palmisano.aibuilder.core.tools;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.palmisano.aibuilder.core.place.Placement;

/** A sink that only remembers what it was given. */
public class RecordingSink implements BuildSink {
	public final List<List<Placement>> batches = new CopyOnWriteArrayList<>();
	public final List<String> checkpoints = new CopyOnWriteArrayList<>();
	public volatile Brief brief;
	public volatile String finishedWith;
	public volatile int placedSoFar = 42;
	/** Runs when a tool waits for everything queued to be placed. */
	public volatile Runnable onAwait = () -> {
	};
	public volatile boolean capReached;
	/** Each part's motion, as animate gave it. */
	public final List<io.palmisano.aibuilder.core.anim.MovingPart> animated = new CopyOnWriteArrayList<>();
	public volatile long pending;
	/** Each gadget placed, as the session will keep it. */
	public final List<io.palmisano.aibuilder.core.gadgets.GadgetUse> gadgets = new CopyOnWriteArrayList<>();

	@Override
	public void gadget(io.palmisano.aibuilder.core.gadgets.GadgetUse use) {
		gadgets.add(use);
	}

	@Override
	public boolean capReached() {
		return capReached;
	}

	@Override
	public long pending() {
		return pending;
	}

	@Override
	public void brief(Brief brief) {
		this.brief = brief;
	}

	@Override
	public void queue(List<Placement> batch) {
		batches.add(batch);
	}

	@Override
	public int checkpoint(String phase) {
		checkpoints.add(phase);
		return placedSoFar;
	}

	@Override
	public void awaitPlaced() {
		onAwait.run();
	}

	@Override
	public void animate(io.palmisano.aibuilder.core.anim.MovingPart part) {
		animated.add(part);
	}

	@Override
	public void finish(String summary) {
		finishedWith = summary;
	}

	public int queuedCount() {
		return batches.stream().mapToInt(List::size).sum();
	}
}
