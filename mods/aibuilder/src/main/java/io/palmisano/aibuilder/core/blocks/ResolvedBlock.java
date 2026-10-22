package io.palmisano.aibuilder.core.blocks;

import java.util.Map;

/** A validated block with its state in vanilla names and values, before the frame's rotation. */
public record ResolvedBlock(BlockInfo info, Map<String, String> state) {
	public ResolvedBlock {
		state = Map.copyOf(state);
	}
}
