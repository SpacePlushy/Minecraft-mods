package io.palmisano.aibuilder.core.shapes;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;

/** One block of a shape, in the local frame. {@code onlyReplacing} is null, a block id or a {@code #tag}. */
public record LocalBlock(Pos pos, BlockSpec block, String onlyReplacing) {
	public LocalBlock(Pos pos, BlockSpec block) {
		this(pos, block, null);
	}
}
