package io.palmisano.aibuilder.core.gadgets;

import java.util.List;

import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;

/**
 * A gadget as generated, facing forward in its own frame (spec v2 §5.3): gx right, gy up, gz forward, {@code (0, 0, 0)}
 * its front-left-bottom block. {@code size} is its width, height and depth; its ports sit just outside that box;
 * {@code settleTicks} is how many game ticks its outputs take to settle after an input changes.
 */
public record Gadget(String kind, Pos size, List<LocalBlock> blocks, List<Port> ports, int settleTicks) {
	public Gadget {
		blocks = List.copyOf(blocks);
		ports = List.copyOf(ports);
	}
}
