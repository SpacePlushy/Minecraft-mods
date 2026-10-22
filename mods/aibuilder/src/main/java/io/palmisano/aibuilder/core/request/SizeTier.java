package io.palmisano.aibuilder.core.request;

import java.util.Locale;

/** The build size the player picks, in the order the screen's size button cycles through them. */
public enum SizeTier {
	AUTO("Auto"),
	SMALL("Small"),
	MEDIUM("Medium"),
	LARGE("Large"),
	EPIC("Epic");

	private final String label;

	SizeTier(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** The tier named {@code name}, in any case; {@code AUTO} for null or an unknown name. */
	public static SizeTier parse(String name) {
		if (name == null) {
			return AUTO;
		}
		try {
			return valueOf(name.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return AUTO;
		}
	}
}
