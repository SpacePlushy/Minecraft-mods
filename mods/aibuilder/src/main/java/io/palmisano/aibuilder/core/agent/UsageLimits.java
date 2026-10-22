package io.palmisano.aibuilder.core.agent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import io.palmisano.aibuilder.core.request.Messages;

/**
 * Paid extra usage: never (Frankie, spec §12.2). Each turn's {@code rate_limit_event} says whether the plan's limit is
 * reached and whether extra usage is being used; either one ends the build at once. A status the mod doesn't know,
 * or none at all, is no evidence of either, so the build goes on (the result would show a real limit anyway).
 */
public final class UsageLimits {
	public enum Kind {
		OK,
		WARN,
		STOP
	}

	/** What to do, and the line for the player ("" for OK). */
	public record Decision(Kind kind, String message) {
	}

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

	private UsageLimits() {
	}

	public static Decision decide(StreamEvent.RateLimit event, ZoneId zone, Instant now) {
		if (event.usingOverage()) {
			return new Decision(Kind.STOP, Messages.extraUsage());
		}
		String status = event.status();
		if (status.isEmpty()) {
			return new Decision(Kind.OK, "");
		}
		if (!status.startsWith("allowed")) {
			return new Decision(Kind.STOP, Messages.usageLimit(resets(event.resetsAt(), zone, now)));
		}
		if (status.equals("allowed_warning")) {
			return new Decision(Kind.WARN, Messages.usageWarning(resets(event.resetsAt(), zone, now)));
		}
		return new Decision(Kind.OK, "");
	}

	/** "at 14:30", "tomorrow at 09:00", "on Oct 24 at 09:00"; "" when the time isn't known. */
	static String resets(long epochSeconds, ZoneId zone, Instant now) {
		if (epochSeconds <= 0) {
			return "";
		}
		ZonedDateTime at = Instant.ofEpochSecond(epochSeconds).atZone(zone);
		LocalDate today = now.atZone(zone).toLocalDate();
		String time = "at " + TIME.format(at);
		if (at.toLocalDate().equals(today)) {
			return time;
		}
		if (at.toLocalDate().equals(today.plusDays(1))) {
			return "tomorrow " + time;
		}
		return "on " + DAY.format(at) + " " + time;
	}
}
