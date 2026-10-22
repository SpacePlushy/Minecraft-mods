package io.palmisano.aibuilder.core.request;

import java.util.Locale;

/** The chat lines the player sees about a prompt. */
public final class Messages {
	private Messages() {
	}

	public static String received(String prompt, SizeTier size) {
		return "AI Builder got: " + prompt + " (" + size.label() + ")";
	}

	public static String problem(BuildPrompt.Problem problem, String prompt) {
		return switch (problem) {
			case EMPTY -> "Type something for AI Builder to build.";
			case TOO_LONG -> String.format(Locale.ROOT, "That prompt is too long: %,d characters, the limit is %,d.",
					prompt.codePointCount(0, prompt.length()), BuildPrompt.MAX_CODE_POINTS);
			case NONE -> throw new IllegalArgumentException("a prompt without a problem has nothing to report");
		};
	}

	public static String ownerOnly() {
		return "Only this world's owner can use AI Builder.";
	}
}
