package io.palmisano.aibuilder.core.agent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.request.Messages;

/**
 * Paid extra usage: never (Frankie, spec §12.2). Claude Code sends a {@code rate_limit_event} with the first response
 * and again whenever the usage state changes (the M4 live build), so it reports extra usage only after the turn that
 * used it: the build stops at once there, and, when the account allows extra usage at all, already when the plan is
 * all but used up. The plan's own limit stops it too. A status the mod doesn't know, or none, is no evidence of
 * either, so the build goes on (the result would show a real limit anyway).
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
	/** Claude Code's words for the plan's limit when it comes as an API error ("You've hit your session limit"). */
	private static final Pattern LIMIT = Pattern.compile("(?i)(hit your [a-z0-9 -]*limit|reached your [a-z0-9 -]*limit|usage limit reached|session limit)");
	private static final Pattern RESETS = Pattern.compile("(?i)resets\\s+([^.·|\\n]+)");

	private UsageLimits() {
	}

	public static Decision decide(StreamEvent.RateLimit event, ZoneId zone, Instant now) {
		if (event.usingOverage() || event.type().equals("overage")) {
			return new Decision(Kind.STOP, Messages.extraUsage());
		}
		String status = event.status();
		if (status.isEmpty()) {
			return new Decision(Kind.OK, "");
		}
		if (!status.startsWith("allowed")) {
			return new Decision(Kind.STOP, Messages.usageLimit(resets(event.resetsAt(), zone, now)));
		}
		boolean extraUsageOn = !event.overageStatus().isEmpty() && !event.overageStatus().equals("rejected");
		if (extraUsageOn && event.utilization() >= Tunables.USAGE_STOP_UTILIZATION) {
			return new Decision(Kind.STOP, Messages.usageLimit(resets(event.resetsAt(), zone, now)));
		}
		if (status.equals("allowed_warning")) {
			return new Decision(Kind.WARN, Messages.usageWarning(resets(event.resetsAt(), zone, now)));
		}
		return new Decision(Kind.OK, "");
	}

	/** The plan's limit in Claude Code's words: its reset time as written ("3pm"), "" if none; empty if it isn't that. */
	public static Optional<String> limitFromText(String text) {
		return text != null && LIMIT.matcher(text).find() ? Optional.of(resetsFromText(text)) : Optional.empty();
	}

	/** "3pm" from "… · resets 3pm", or "". */
	public static String resetsFromText(String text) {
		Matcher m = RESETS.matcher(text == null ? "" : text);
		return m.find() ? m.group(1).strip() : "";
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
