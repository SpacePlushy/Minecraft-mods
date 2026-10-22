package io.palmisano.aibuilder.core.agent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes secrets out of text bound for a log (spec §16): the build's own token wherever it appears, any bearer token,
 * and anything shaped like an Anthropic key ({@code sk-ant-…}). What it puts in their place is JSON-safe, so a
 * scrubbed JSON line stays JSON. Thread-safe: the backend adds its token while other threads scrub.
 */
public final class Scrub {
	public static final String REDACTED = "[redacted]";
	/** Shorter secrets would take ordinary words with them. */
	static final int MIN_SECRET = 8;
	private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]{16,}");
	private static final Pattern ANTHROPIC_KEY = Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{8,}");

	private final List<String> secrets = new CopyOnWriteArrayList<>();

	/** A secret to take out wherever it appears, such as the build's token. */
	public void add(String secret) {
		if (secret != null && secret.length() >= MIN_SECRET && !secrets.contains(secret)) {
			secrets.add(secret);
		}
	}

	public String apply(String text) {
		String out = text;
		for (String secret : secrets) {
			out = out.replace(secret, REDACTED);
		}
		out = ANTHROPIC_KEY.matcher(out).replaceAll(Matcher.quoteReplacement(REDACTED));
		return BEARER.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(REDACTED));
	}
}
