package io.palmisano.aibuilder.core.shapes;

import io.palmisano.aibuilder.core.blocks.BlockEntityData;
import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;

/**
 * One block of a shape, in the local frame. {@code onlyReplacing} is null, a block id or a {@code #tag};
 * {@code data} is null, or what place_block_entity gives its block entity.
 */
public record LocalBlock(Pos pos, BlockSpec block, String onlyReplacing, BlockEntityData data) {
	public LocalBlock(Pos pos, BlockSpec block) {
		this(pos, block, null, null);
	}

	public LocalBlock(Pos pos, BlockSpec block, String onlyReplacing) {
		this(pos, block, onlyReplacing, null);
	}
}
