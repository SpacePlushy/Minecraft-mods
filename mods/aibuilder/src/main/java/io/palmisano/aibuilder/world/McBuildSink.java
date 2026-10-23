package io.palmisano.aibuilder.world;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.anim.MovingPart;
import io.palmisano.aibuilder.core.place.Placement;
import io.palmisano.aibuilder.core.tools.Brief;
import io.palmisano.aibuilder.core.tools.BuildSink;
import io.palmisano.aibuilder.core.tools.PhaseProgress;

/**
 * Hands the write tools' batches to a placement job, a checkpoint waiting for the job to catch up, and a moving part's
 * motion to {@code onAnimate} (from the backend's thread).
 */
public final class McBuildSink implements BuildSink {
	private final PlacementJob job;
	private final Consumer<Brief> onBrief;
	private final Consumer<String> onFinish;
	private final Consumer<MovingPart> onAnimate;
	/** Written by the backend's thread, read by the status line. */
	private volatile PhaseProgress phases = PhaseProgress.of(List.of());

	public McBuildSink(PlacementJob job, Consumer<Brief> onBrief, Consumer<String> onFinish, Consumer<MovingPart> onAnimate) {
		this.job = job;
		this.onBrief = onBrief;
		this.onFinish = onFinish;
		this.onAnimate = onAnimate;
	}

	/** The phase in progress, e.g. "walls (2/7)", for the status line. Any thread. */
	public String phase() {
		return phases.label();
	}

	/** The brief's phases and the one under way, for the HUD. Any thread. */
	public PhaseProgress progress() {
		return phases;
	}

	@Override
	public void brief(Brief brief) {
		phases = PhaseProgress.of(brief.phases());
		onBrief.accept(brief);
	}

	@Override
	public void queue(List<Placement> batch) {
		job.enqueue(batch);
	}

	@Override
	public void animate(MovingPart part) {
		onAnimate.accept(part);
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
		phases = phases.checkpointed(phase);
		return job.placed();
	}

	@Override
	public void awaitPlaced() {
		try {
			job.whenIdle().get(Tunables.TERRAFORM_PLACE_WAIT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException | TimeoutException e) {
			// As for a checkpoint: go on with what's there.
		}
	}

	@Override
	public boolean capReached() {
		return job.capReached();
	}

	@Override
	public long pending() {
		return job.pending();
	}

	@Override
	public void finish(String summary) {
		onFinish.accept(summary);
	}
}
