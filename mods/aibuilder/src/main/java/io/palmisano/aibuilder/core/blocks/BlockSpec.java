package io.palmisano.aibuilder.core.blocks;

import java.util.Map;

/** A block as Claude names it: an id and a state written in relative direction words. */
public record BlockSpec(String id, Map<String, String> state) {
	public BlockSpec {
		state = Map.copyOf(state);
	}

	public static BlockSpec of(String id) {
		return new BlockSpec(id, Map.of());
	}
}
