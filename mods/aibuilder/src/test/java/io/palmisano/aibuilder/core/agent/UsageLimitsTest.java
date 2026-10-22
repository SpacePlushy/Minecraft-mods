package io.palmisano.aibuilder.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import io.palmisano.aibuilder.core.request.Messages;
import org.junit.jupiter.api.Test;

/** Paid extra usage never, and a clear stop at the plan's limit (spec §12.2, the M4 spike). */
class UsageLimitsTest {
	private static final ZoneId ZONE = ZoneId.of("America/New_York");
	/** 2026-10-22 10:00 in New York. */
	private static final Instant NOW = ZonedDateTime.of(2026, 10, 22, 10, 0, 0, 0, ZONE).toInstant();

	private static long at(int day, int hour, int minute) {
		return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, ZONE).toEpochSecond();
	}

	private static UsageLimits.Decision decide(String status, long resetsAt, boolean overage) {
		return UsageLimits.decide(new StreamEvent.RateLimit(status, resetsAt, "five_hour", overage, "rejected", 0.5), ZONE, NOW);
	}

	/** Claude Code reports a window of its own once extra usage is in play: stop there too (M4 review). */
	@Test
	void anOverageWindowStops() {
		UsageLimits.Decision d = UsageLimits.decide(new StreamEvent.RateLimit("allowed", at(22, 14, 30), "overage", false, "allowed", 0.1),
				ZONE, NOW);
		assertEquals(UsageLimits.Kind.STOP, d.kind());
		assertEquals(Messages.extraUsage(), d.message());
	}

	/**
	 * Claude Code says so only after the turn that used it, so with extra usage on, a plan that's all but used up
	 * stops the build before that turn (M4 review); with extra usage off the plan's own limit stops it, nothing earlier.
	 */
	@Test
	void nearTheLimitWithExtraUsageOnStopsEarly() {
		UsageLimits.Decision on = UsageLimits.decide(new StreamEvent.RateLimit("allowed", at(22, 14, 30), "five_hour", false, "allowed", 0.97),
				ZONE, NOW);
		assertEquals(UsageLimits.Kind.STOP, on.kind());
		assertEquals("Your Claude usage limit is reached (resets at 14:30). What's built so far stays; /aibuild undo reverts it.", on.message());
		assertEquals(UsageLimits.Kind.OK, UsageLimits.decide(new StreamEvent.RateLimit("allowed", at(22, 14, 30), "five_hour", false,
				"allowed", 0.90), ZONE, NOW).kind());
		assertEquals(UsageLimits.Kind.OK, UsageLimits.decide(new StreamEvent.RateLimit("allowed", at(22, 14, 30), "five_hour", false,
				"rejected", 0.99), ZONE, NOW).kind());
	}

	@Test
	void allowedIsFine() {
		assertEquals(UsageLimits.Kind.OK, decide("allowed", at(22, 14, 30), false).kind());
	}

	@Test
	void aWarningOnlyWarns() {
		UsageLimits.Decision d = decide("allowed_warning", at(22, 14, 30), false);
		assertEquals(UsageLimits.Kind.WARN, d.kind());
		assertEquals("Claude usage is close to its limit (resets at 14:30).", d.message());
	}

	@Test
	void theLimitStopsTheBuild() {
		UsageLimits.Decision d = decide("rejected", at(22, 14, 30), false);
		assertEquals(UsageLimits.Kind.STOP, d.kind());
		assertEquals("Your Claude usage limit is reached (resets at 14:30). What's built so far stays; /aibuild undo reverts it.", d.message());
	}

	@Test
	void anyStatusButAllowedStops() {
		assertEquals(UsageLimits.Kind.STOP, decide("blocked_for_reasons", at(22, 14, 30), false).kind());
	}

	@Test
	void noStatusIsNoEvidence() {
		// A later Claude Code that moved the field: no proof of a limit, so the build goes on (overage still stops it).
		assertEquals(UsageLimits.Kind.OK, decide("", 0, false).kind());
		assertEquals(UsageLimits.Kind.STOP, decide("", 0, true).kind());
	}

	@Test
	void extraUsageStopsTheBuildEvenWhenAllowed() {
		UsageLimits.Decision d = decide("allowed", at(22, 14, 30), true);
		assertEquals(UsageLimits.Kind.STOP, d.kind());
		// Honest about timing: Claude Code reports it after the turn that used it.
		assertEquals("Claude started using paid extra usage, so the build stopped at once: AI Builder never builds on it. What's built so far"
				+ " stays; /aibuild undo reverts it.", d.message());
	}

	/** Spec §12.2: the limit can come as an API error in Claude Code's own words; Claude's ordinary words don't count. */
	@Test
	void limitTextIsRecognised() {
		assertEquals(java.util.Optional.of("3pm"), UsageLimits.limitFromText("You've hit your session limit · resets 3pm"));
		assertEquals(java.util.Optional.of(""), UsageLimits.limitFromText("Claude AI usage limit reached|1792681200"));
		assertEquals(java.util.Optional.empty(), UsageLimits.limitFromText("The castle is done, well within the size limit."));
		assertEquals(java.util.Optional.empty(), UsageLimits.limitFromText(""));
	}

	@Test
	void resetTimesReadNaturally() {
		assertTrue(decide("rejected", at(23, 9, 0), false).message().contains("(resets tomorrow at 09:00)"));
		assertTrue(decide("rejected", at(24, 9, 0), false).message().contains("(resets on Oct 24 at 09:00)"));
		// No reset time known: say nothing about when.
		assertEquals("Your Claude usage limit is reached. What's built so far stays; /aibuild undo reverts it.", decide("rejected", 0, false).message());
	}
}
