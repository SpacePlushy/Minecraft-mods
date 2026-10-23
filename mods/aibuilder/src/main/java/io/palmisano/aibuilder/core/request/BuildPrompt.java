package io.palmisano.aibuilder.core.request;

/** What counts as a usable prompt. */
public final class BuildPrompt {
	/** The longest prompt, in characters (code points), so an emoji counts once. */
	public static final int MAX_CODE_POINTS = 2000;

	public enum Problem {
		NONE,
		EMPTY,
		TOO_LONG
	}

	private BuildPrompt() {
	}

	/**
	 * The prompt as the player meant it: outer whitespace removed, inner newlines kept, and half an emoji (a lone
	 * surrogate, as the screen's box leaves when it cuts one at its limit) dropped, so it never goes out as "?".
	 */
	public static String normalize(String raw) {
		if (raw == null) {
			return "";
		}
		StringBuilder kept = new StringBuilder(raw.length());
		raw.codePoints().filter(c -> c < Character.MIN_SURROGATE || c > Character.MAX_SURROGATE).forEach(kept::appendCodePoint);
		return kept.toString().strip();
	}

	public static Problem check(String normalized) {
		if (normalized.isEmpty()) {
			return Problem.EMPTY;
		}
		return normalized.codePointCount(0, normalized.length()) > MAX_CODE_POINTS ? Problem.TOO_LONG : Problem.NONE;
	}
}
