package io.palmisano.aibuilder.core.request;

import java.util.Locale;
import java.util.Set;

/**
 * Which {@code /aibuild} commands a click may run. A click (a book page, a sign, a tellraw link in a downloaded map)
 * runs a command without the player typing it, so it could send someone else's prompt through the owner's Claude login
 * (M4 review, spec §15). Prompts and builds must be typed; the mod's own buttons ([Undo]) and reads still go through.
 */
public final class ClickedCommands {
	private static final Set<String> ALLOWED = Set.of("undo", "cancel", "status", "survey");

	private ClickedCommands() {
	}

	/** {@code command}: as clicked, with or without the slash. */
	public static boolean refused(String command) {
		String[] words = command.strip().replaceFirst("^/", "").split("\\s+", 3);
		if (!words[0].toLowerCase(Locale.ROOT).equals("aibuild") || words.length == 1) {
			return false;
		}
		return words.length > 2 || !ALLOWED.contains(words[1].toLowerCase(Locale.ROOT));
	}
}
