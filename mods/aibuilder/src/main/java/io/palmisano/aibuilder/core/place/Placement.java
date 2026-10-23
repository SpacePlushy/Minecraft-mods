package io.palmisano.aibuilder.core.place;

import io.palmisano.aibuilder.core.blocks.BlockEntityData;
import io.palmisano.aibuilder.core.blocks.PlacementPass;
import io.palmisano.aibuilder.core.blocks.ResolvedBlock;
import io.palmisano.aibuilder.core.frame.Pos;

/**
 * One block to place, at a world position. {@code onlyReplacing} is null, a block id or a {@code #tag};
 * {@code data} is null or what its block entity gets (place_block_entity). An item frame is a placement with no
 * block: an entity, at that position. A placement with a {@code part} ("" for none) is a moving part's display of
 * the block (spec v2 §4.1): an entity too, changing no block.
 */
public record Placement(Pos pos, ResolvedBlock block, String onlyReplacing, BlockEntityData data, String part) {
	public Placement {
		part = part == null ? "" : part;
	}

	public Placement(Pos pos, ResolvedBlock block, String onlyReplacing, BlockEntityData data) {
		this(pos, block, onlyReplacing, data, "");
	}

	public Placement(Pos pos, ResolvedBlock block, String onlyReplacing) {
		this(pos, block, onlyReplacing, null, "");
	}

	/** Item frames hang on what's built, so they go with the attached blocks (spec §9); displays go in with them. */
	public PlacementPass pass() {
		return block == null || !part.isEmpty() ? PlacementPass.ATTACHED : block.info().pass();
	}
}
