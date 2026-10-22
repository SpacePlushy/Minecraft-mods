package io.palmisano.aibuilder.core.request;

import java.util.Locale;

/**
 * The build size the player picks, in the order the screen's size button cycles through them. {@link #size()} is
 * the allowed box's width, depth and height; Auto aims for Medium but is capped at Large.
 */
public enum SizeTier {
	AUTO("Auto", 80, 350_000),
	SMALL("Small", 24, 15_000),
	MEDIUM("Medium", 48, 100_000),
	LARGE("Large", 80, 350_000),
	EPIC("Epic", 128, 1_000_000);

	private final String label;
	private final int size;
	private final long maxChangedBlocks;

	SizeTier(String label, int size, long maxChangedBlocks) {
		this.label = label;
		this.size = size;
		this.maxChangedBlocks = maxChangedBlocks;
	}

	public String label() {
		return label;
	}

	public int size() {
		return size;
	}

	public long maxChangedBlocks() {
		return maxChangedBlocks;
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
