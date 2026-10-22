package io.palmisano.aibuilder.core.place;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Each player's undoable builds, newest first; past {@code max} the oldest is dropped. */
public final class UndoHistory<J> {
	private final int max;
	private final Map<UUID, Deque<J>> byPlayer = new HashMap<>();

	public UndoHistory(int max) {
		this.max = max;
	}

	public void push(UUID player, J entry) {
		Deque<J> builds = byPlayer.computeIfAbsent(player, p -> new ArrayDeque<>());
		builds.addFirst(entry);
		while (builds.size() > max) {
			builds.removeLast();
		}
	}

	public Optional<J> pop(UUID player) {
		Deque<J> builds = byPlayer.get(player);
		return builds == null ? Optional.empty() : Optional.ofNullable(builds.pollFirst());
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
