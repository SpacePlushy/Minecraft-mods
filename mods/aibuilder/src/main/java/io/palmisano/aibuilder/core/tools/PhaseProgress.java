package io.palmisano.aibuilder.core.tools;

import java.util.List;

/**
 * Which of the brief's phases is being built, for the status line ("walls (2/7)", spec §5.3). Claude checkpoints at
 * the end of each phase, so after a checkpoint the next phase is in progress; a checkpoint named after a later
 * phase skips ahead to the one after it.
 */
public final class PhaseProgress {
	private final List<String> phases;
	private final int current;
	private final String lastCheckpoint;

	private PhaseProgress(List<String> phases, int current, String lastCheckpoint) {
		this.phases = phases;
		this.current = current;
		this.lastCheckpoint = lastCheckpoint;
	}

	public static PhaseProgress of(List<String> phases) {
		return new PhaseProgress(List.copyOf(phases), 0, "");
	}

	public PhaseProgress checkpointed(String phase) {
		int named = phases.indexOf(phase);
		int next = Math.min((named >= 0 ? named : current) + 1, Math.max(phases.size() - 1, 0));
		return new PhaseProgress(phases, next, phase);
	}

	/** "walls (2/7)"; without phases in the brief, the last checkpoint's name, or "" before the first. */
	public String label() {
		if (phases.isEmpty()) {
			return lastCheckpoint;
		}
		return phases.get(current) + " (" + (current + 1) + "/" + phases.size() + ")";
	}
}
