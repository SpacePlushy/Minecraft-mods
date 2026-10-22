package io.palmisano.aibuilder.core.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The build's caps (spec §12.2), the mod's own: tool calls, output tokens and minutes. Once any one is used up the
 * build is over budget; results warn Claude as each runs low.
 */
public final class Budget {
	private final int maxToolCalls;
	private final long maxOutputTokens;
	private final long maxMillis;
	private final LongSupplier clock;
	private final long start;
	/** Counted on the backend's thread, read by the status line on the server thread. */
	private volatile int used;
	/** Added from Claude Code's stream, on its reader thread. */
	private final AtomicLong outputTokens = new AtomicLong();

	/** Tool calls only, no token or time limit (scripted builds). */
	public Budget(int maxToolCalls) {
		this(maxToolCalls, Long.MAX_VALUE, Long.MAX_VALUE, System::currentTimeMillis);
	}

	public Budget(int maxToolCalls, long maxOutputTokens, long maxMillis, LongSupplier millisClock) {
		this.maxToolCalls = maxToolCalls;
		this.maxOutputTokens = maxOutputTokens;
		this.maxMillis = maxMillis;
		this.clock = millisClock;
		this.start = millisClock.getAsLong();
	}

	public void count() {
		used++;
	}

	public int used() {
		return used;
	}

	public int max() {
		return maxToolCalls;
	}

	public void addOutputTokens(long tokens) {
		outputTokens.addAndGet(tokens);
	}

	public long outputTokens() {
		return outputTokens.get();
	}

	public boolean exhausted() {
		return !limitReached().isEmpty();
	}

	/** Which cap is used up, for the player ("the 150 tool-call limit"); "" while none is. */
	public String limitReached() {
		if (used > maxToolCalls) {
			return "the " + number(maxToolCalls) + " tool-call limit";
		}
		if (outputTokens.get() >= maxOutputTokens) {
			return "the " + number(maxOutputTokens) + "-token limit";
		}
		if (elapsed() >= maxMillis) {
			return "the " + maxMillis / 60_000 + "-minute limit";
		}
		return "";
	}

	/** Empty while every budget has a quarter or more left; then what's low; below a tenth of any, "finish now". */
	public String footer() {
		if (exhausted()) {
			return "";
		}
		int callsLeft = maxToolCalls - used;
		long tokensLeft = maxOutputTokens - outputTokens.get();
		long millisLeft = maxMillis - elapsed();
		if (callsLeft < maxToolCalls * 0.10 || tokensLeft < maxOutputTokens * 0.10 || millisLeft < maxMillis * 0.10) {
			return "Budget nearly spent: finish now.";
		}
		List<String> low = new ArrayList<>();
		if (callsLeft < maxToolCalls * 0.25) {
			low.add(callsLeft + (callsLeft == 1 ? " tool call left" : " tool calls left"));
		}
		if (tokensLeft < maxOutputTokens * 0.25) {
			low.add("about " + number(tokensLeft) + " output tokens left");
		}
		if (millisLeft < maxMillis * 0.25) {
			long minutes = millisLeft / 60_000;
			low.add(minutes + (minutes == 1 ? " minute left" : " minutes left"));
		}
		return low.isEmpty() ? "" : "Budget: " + String.join(", ", low) + ".";
	}

	private long elapsed() {
		return clock.getAsLong() - start;
	}

	private static String number(long n) {
		return String.format(Locale.ROOT, "%,d", n);
	}
}
