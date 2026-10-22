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

	/** The prompt as the player meant it: outer whitespace removed, inner newlines kept. */
	public static String normalize(String raw) {
		return raw == null ? "" : raw.strip();
	}

	public static Problem check(String normalized) {
		if (normalized.isEmpty()) {
			return Problem.EMPTY;
		}
		return normalized.codePointCount(0, normalized.length()) > MAX_CODE_POINTS ? Problem.TOO_LONG : Problem.NONE;
	}
}
