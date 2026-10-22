package io.palmisano.aibuilder.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** rate_limit_per_hour (spec §15): a guard against a loop of prompts, per player, over the last hour. */
class RateLimitTest {
	private static final UUID PLAYER = UUID.fromString("0f6a1c2e-0000-4000-8000-000000000001");
	private static final long MINUTE = 60_000;

	@Test
	void theTwentyFirstPromptInAnHourWaits() {
		RateLimit limit = new RateLimit(20);
		for (int i = 0; i < 20; i++) {
			assertTrue(limit.tryStart(PLAYER, i * MINUTE).isEmpty(), "prompt " + (i + 1));
		}
		OptionalLong wait = limit.tryStart(PLAYER, 20 * MINUTE);
		assertEquals(40 * MINUTE, wait.orElseThrow(), "the first one leaves the hour 40 minutes from now");
		assertTrue(limit.tryStart(PLAYER, 60 * MINUTE).isEmpty(), "an hour after the first, there's room again");
	}

	@Test
	void aRefusedPromptDoesntCount() {
		RateLimit limit = new RateLimit(1);
		assertTrue(limit.tryStart(PLAYER, 0).isEmpty());
		assertTrue(limit.tryStart(PLAYER, MINUTE).isPresent());
		assertTrue(limit.tryStart(PLAYER, 60 * MINUTE).isEmpty());
	}

	@Test
	void zeroIsNoLimit() {
		RateLimit limit = new RateLimit(0);
		for (int i = 0; i < 1_000; i++) {
			assertTrue(limit.tryStart(PLAYER, i).isEmpty());
		}
	}

	@Test
	void playersCountApart() {
		RateLimit limit = new RateLimit(1);
		assertTrue(limit.tryStart(PLAYER, 0).isEmpty());
		assertTrue(limit.tryStart(UUID.randomUUID(), 0).isEmpty());
	}
}
