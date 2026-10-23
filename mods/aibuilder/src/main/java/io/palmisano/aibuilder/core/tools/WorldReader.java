package io.palmisano.aibuilder.core.tools;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.palmisano.aibuilder.core.blocks.ItemCatalog;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.FireMask;
import io.palmisano.aibuilder.core.place.ProtectionMask;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.survey.BuildProblems;
import io.palmisano.aibuilder.core.survey.TerrainSnapshot;
import io.palmisano.aibuilder.core.wire.Cell;

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

	/** The columns of a local area, minX..minX+width−1 by minZ..minZ+depth−1, as read when the build started. */
	TerrainSnapshot terrain(int minX, int minZ, int width, int depth);

	/** The same, read again now: what terraform levels, after the build's own changes. */
	TerrainSnapshot currentTerrain(int minX, int minZ, int width, int depth);

	/** Exact block states in a local box, directions relative; x fastest, then z, then y. */
	List<String> states(Box local);

	WorldFacts facts();

	/** Protected spots in the allowed box, read when the build started. */
	ProtectionMask protection();

	/** Lava and flammable blocks in the allowed box, read when the build started (spec §9). */
	FireMask fire();

	/** Which of these local positions hold a solid block now (check_motion: what a moving part would pass through). */
	Set<Pos> solid(Set<Pos> local);

	/** What each of these local positions holds now, as a wire sees it (spec v2 §5.4); one not loaded is {@link Cell#OTHER}. */
	Map<Pos, Cell> cells(Set<Pos> local);

	/**
	 * test_redstone (spec v2 §5.2): the steps run on the build's machine in the world, and the report. A step the
	 * machine can't take (a lever that isn't the build's, a rule broken, a player in the way) is refused with an
	 * IllegalArgumentException saying why.
	 */
	String testRedstone(List<RedstoneSteps.Step> steps);

	/** The build looked over as it stands now (spec §8): its problems, at local positions. */
	List<BuildProblems.Problem> check();

	/** The game's items and banner patterns (the world's own, data packs included), for place_block_entity. */
	ItemCatalog items();
}
