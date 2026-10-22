package io.palmisano.aibuilder.core.place;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Each player's undoable builds, newest first. A build goes in as it starts (saved with the world, so a crash can still
 * be undone) and counts once it has something to undo: only then is the history trimmed to undo_history_size, so a
 * build that changes nothing and leaves again never costs an older one its undo (PR review). {@code max} is the
 * ceiling, with room for the running build.
 */
public final class UndoHistory<J> {
	private final int max;
	private final Map<UUID, Deque<J>> byPlayer = new HashMap<>();

	public UndoHistory(int max) {
		this.max = max;
	}

	/** A build, newest first; nothing older is dropped below the ceiling (and one more, the running build). */
	public void push(UUID player, J entry) {
		Deque<J> builds = byPlayer.computeIfAbsent(player, p -> new ArrayDeque<>());
		builds.addFirst(entry);
		while (builds.size() > max + 1) {
			builds.removeLast();
		}
	}

	/** Keeps the newest {@code keep} (undo_history_size), never more than {@code max}: when a build has counted. */
	public void trim(UUID player, int keep) {
		Deque<J> builds = byPlayer.get(player);
		if (builds == null) {
			return;
		}
		while (builds.size() > Math.max(1, Math.min(keep, max))) {
			builds.removeLast();
		}
	}

	public Optional<J> pop(UUID player) {
		Deque<J> builds = byPlayer.get(player);
		return builds == null ? Optional.empty() : Optional.ofNullable(builds.pollFirst());
	}

	/** The newest build, left in place. */
	public Optional<J> peek(UUID player) {
		Deque<J> builds = byPlayer.get(player);
		return builds == null ? Optional.empty() : Optional.ofNullable(builds.peekFirst());
	}

	/** Takes out that one build, wherever it is in the history. */
	public void remove(UUID player, J entry) {
		Deque<J> builds = byPlayer.get(player);
		if (builds != null) {
			builds.removeFirstOccurrence(entry);
		}
	}

	/** Puts {@code replacement} where {@code entry} was. */
	public void replace(UUID player, J entry, J replacement) {
		Deque<J> builds = byPlayer.get(player);
		if (builds == null || !builds.contains(entry)) {
			return;
		}
		List<J> inOrder = new ArrayList<>(builds);
		inOrder.set(inOrder.indexOf(entry), replacement);
		builds.clear();
		builds.addAll(inOrder);
	}

	public int size(UUID player) {
		Deque<J> builds = byPlayer.get(player);
		return builds == null ? 0 : builds.size();
	}

	/** Everyone's builds, newest first per player, for saving. */
	public Map<UUID, Deque<J>> all() {
		return byPlayer;
	}
}
