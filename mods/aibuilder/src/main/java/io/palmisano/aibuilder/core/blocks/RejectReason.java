package io.palmisano.aibuilder.core.blocks;

import java.util.Locale;

/** Why an entry was refused; Claude sees {@link #wireName()}. */
public enum RejectReason {
	UNKNOWN_BLOCK,
	BAD_PROPERTY,
	BAD_VALUE,
	COMPASS_DIRECTION,
	BANNED_BLOCK,
	LAVA_NEAR_FLAMMABLE,
	OUT_OF_BOUNDS,
	PROTECTED,
	TOO_MANY,
	BAD_ARGUMENT;

	public String wireName() {
		return name().toLowerCase(Locale.ROOT);
	}
}
