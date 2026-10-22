package io.palmisano.aibuilder.core.request;

import java.util.Optional;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;

/**
 * Refine or build new (spec §5.4): a prompt refines the player's last build when they're within 48 blocks of it,
 * sideways, in the same dimension, unless they asked for a new one ({@code /aibuild new}, the screen's toggle).
 */
public final class Refinement {
	private Refinement() {
	}

	public static Optional<BuildSession> decide(BuildSession last, boolean forceNew, Pos playerWorld, String dimension) {
		if (forceNew || last == null || !last.dimension().equals(dimension)) {
			return Optional.empty();
		}
		Pos local = new LocalFrame(last.origin(), last.facing()).toLocal(playerWorld);
		long dx = outside(local.x(), last.changed().min().x(), last.changed().max().x());
		long dz = outside(local.z(), last.changed().min().z(), last.changed().max().z());
		return dx * dx + dz * dz <= (long) Tunables.REFINE_REACH * Tunables.REFINE_REACH ? Optional.of(last) : Optional.empty();
	}

	/** What Claude reads for a refinement: the build, what to change, and that the box and the player may have moved. */
	public static String prompt(BuildSession session, String prompt) {
		String name = session.title().isBlank() ? "the build" : "\"" + session.title() + "\"";
		return "The player wants a change to " + name + ", the build you made here: " + prompt + "\n\n"
				+ (session.lastUndone() ? "The player undid your last change, so the build is as it was before it.\n\n" : "")
				+ "The allowed box is now everything built so far, grown by " + Tunables.REFINE_GROW
				+ " blocks, and the player may be standing somewhere else: call get_context first. Change what this asks for and"
				+ " keep the rest; submit_brief again with the phases of this change.";
	}

	private static long outside(int v, int min, int max) {
		return v < min ? min - v : v > max ? v - max : 0;
	}
}
