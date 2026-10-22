package io.palmisano.aibuilder.core.place;

import io.palmisano.aibuilder.core.blocks.ResolvedBlock;
import io.palmisano.aibuilder.core.frame.Pos;

/** One block to place, at a world position. {@code onlyReplacing} is null, a block id or a {@code #tag}. */
public record Placement(Pos pos, ResolvedBlock block, String onlyReplacing) {
}
