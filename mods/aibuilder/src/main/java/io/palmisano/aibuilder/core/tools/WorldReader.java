package io.palmisano.aibuilder.core.tools;

import java.util.List;

import io.palmisano.aibuilder.core.place.ProtectionMask;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.survey.TerrainSnapshot;

/**
 * The world as the tools see it, in the build's local frame. Called from the backend's thread; the implementation
 * reads the world on the server thread and waits for it, with a timeout.
 */
public interface WorldReader {
	/** The world couldn't be read (in time, or at all); the tool says so and Claude carries on. */
	final class Unreadable extends RuntimeException {
		public Unreadable(String message) {
			super(message);
		}
	}

	/** The columns of a local area, minX..minX+width−1 by minZ..minZ+depth−1. */
	TerrainSnapshot terrain(int minX, int minZ, int width, int depth);

	/** Exact block states in a local box, directions relative; x fastest, then z, then y. */
	List<String> states(Box local);

	WorldFacts facts();

	/** Protected spots in the allowed box, read when the build started. */
	ProtectionMask protection();

	/** A world that can't be read: nothing is protected, and the read tools say so. */
	WorldReader NONE = new WorldReader() {
		@Override
		public TerrainSnapshot terrain(int minX, int minZ, int width, int depth) {
			throw new Unreadable("the world can't be read here");
		}

		@Override
		public List<String> states(Box local) {
			throw new Unreadable("the world can't be read here");
		}

		@Override
		public WorldFacts facts() {
			throw new Unreadable("the world can't be read here");
		}

		@Override
		public ProtectionMask protection() {
			return ProtectionMask.none();
		}
	};
}
