package io.palmisano.aibuilder.world;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.place.Placement;
import io.palmisano.aibuilder.core.tools.Brief;
import io.palmisano.aibuilder.core.tools.BuildSink;

/** Hands the write tools' batches to a placement job; a checkpoint waits for the job to catch up. */
public final class McBuildSink implements BuildSink {
	private final PlacementJob job;
	private final Consumer<Brief> onBrief;
	private final Consumer<String> onFinish;

	public McBuildSink(PlacementJob job, Consumer<Brief> onBrief, Consumer<String> onFinish) {
		this.job = job;
		this.onBrief = onBrief;
		this.onFinish = onFinish;
	}

	@Override
	public void brief(Brief brief) {
		onBrief.accept(brief);
	}

	@Override
	public void queue(List<Placement> batch) {
		job.enqueue(batch);
	}

	/** Blocks the backend's thread until the job has placed everything queued, or for at most the checkpoint timeout. */
	@Override
	public int checkpoint(String phase) {
		try {
			job.whenIdle().get(Tunables.CHECKPOINT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException | TimeoutException e) {
			// A player who won't move, or a job that died, doesn't stop Claude: report what's placed so far.
		}
		return job.placed();
	}

	@Override
	public void finish(String summary) {
		onFinish.accept(summary);
	}
}
