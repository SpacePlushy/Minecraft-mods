package io.palmisano.aibuilder.core.tools;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which of the brief's phases is being built, for the status line ("walls (2/7)", spec §5.3). Claude checkpoints at
 * the end of each phase, so after a checkpoint the next phase is in progress. A checkpoint that names phases (their
 * names, or just their first words: "gatehouse and keep") moves past the last of them, never back; one that names
 * none ends the phase in progress.
 */
public final class PhaseProgress {
	/** Words that don't name a phase on their own. */
	private static final Set<String> FILLER = Set.of("and", "the", "with", "from", "into", "over", "then", "more", "some", "each", "build",
			"building", "make", "making", "add", "adding", "finish", "final", "first", "last", "main");

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
		int named = lastNamed(phase);
		int next = named >= 0 ? Math.max(current, named + 1) : current + 1;
		return new PhaseProgress(phases, Math.min(next, Math.max(phases.size() - 1, 0)), phase);
	}

	/** The last phase the checkpoint names, or -1. */
	private int lastNamed(String checkpoint) {
		String said = checkpoint.toLowerCase(Locale.ROOT).strip();
		Set<String> saidWords = Set.of(said.split("[^a-z0-9]+"));
		int last = -1;
		for (int i = 0; i < phases.size(); i++) {
			String phase = phases.get(i).toLowerCase(Locale.ROOT).strip();
			if (phase.isEmpty()) {
				continue;
			}
			// Part of a phase's name counts too ("walls" for "stone walls"), unless it's too short to mean anything.
			boolean named = said.contains(phase) || said.length() >= 4 && phase.contains(said);
			if (!named) {
				// Its first word that means something: "gatehouse" for "gatehouse with drum towers".
				for (String word : phase.split("[^a-z0-9]+")) {
					if (word.length() >= 4 && !FILLER.contains(word)) {
						named = saidWords.contains(word);
						break;
					}
				}
			}
			if (named) {
				last = i;
			}
		}
		return last;
	}

	public List<String> phases() {
		return phases;
	}

	/** The phase under way, counted from 0: as many as are done. */
	public int current() {
		return current;
	}

	/** "walls (2/7)"; without phases in the brief, the last checkpoint's name, or "" before the first. */
	public String label() {
		if (phases.isEmpty()) {
			return lastCheckpoint;
		}
		return phases.get(current) + " (" + (current + 1) + "/" + phases.size() + ")";
	}
}
