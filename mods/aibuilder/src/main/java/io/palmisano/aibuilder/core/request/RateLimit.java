package io.palmisano.aibuilder.core.request;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * {@code rate_limit_per_hour} (spec §15): builds a player may start in any hour, a guard against a loop of prompts.
 * 0 means no limit. Not thread-safe: the server thread owns it.
 */
public final class RateLimit {
	private static final long HOUR = 3_600_000;

	private final int perHour;
	private final Map<UUID, Deque<Long>> starts = new HashMap<>();

	public RateLimit(int perHour) {
		this.perHour = perHour;
	}

	/** Counts a start and returns empty; or, at the limit, how many milliseconds until there's room (and counts nothing). */
	public OptionalLong tryStart(UUID player, long nowMillis) {
		if (perHour <= 0) {
			return OptionalLong.empty();
		}
		Deque<Long> recent = starts.computeIfAbsent(player, p -> new ArrayDeque<>());
		while (!recent.isEmpty() && recent.peekFirst() <= nowMillis - HOUR) {
			recent.pollFirst();
		}
		if (recent.size() >= perHour) {
			return OptionalLong.of(recent.peekFirst() + HOUR - nowMillis);
		}
		recent.addLast(nowMillis);
		return OptionalLong.empty();
	}
}
