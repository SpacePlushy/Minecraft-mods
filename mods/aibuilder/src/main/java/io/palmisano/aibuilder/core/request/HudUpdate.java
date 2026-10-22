package io.palmisano.aibuilder.core.request;

/**
 * What the build's status panel needs this second (spec §5.3): the owner has it while they're alive and in the
 * build's world; it comes down while they're dead or elsewhere, and back when they return.
 */
public enum HudUpdate {
	/** Send the status: the panel is (or goes back) up. */
	SEND,
	/** Take the panel down: it's up, and they're dead or in another world. */
	CLEAR,
	/** Nothing: it's down already. */
	NOTHING;

	public static HudUpdate decide(boolean alive, boolean sameWorld, boolean shown) {
		if (alive && sameWorld) {
			return SEND;
		}
		return shown ? CLEAR : NOTHING;
	}
}
