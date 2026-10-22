package io.palmisano.aibuilder.core.blocks;

import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.frame.Pos;

/**
 * The other blocks a two-block block writes, in the local frame, as {@code StateFactory} writes them in the world: a
 * door's or a tall plant's top half (its {@code half} is lower or upper) above it, and a bed's head (its {@code part}
 * is head or foot) the way it faces. Placed as its top half or its head, it's the one block. The state is before the
 * frame's rotation, where south is forward (+z) and west is right (+x). The tools check these as they check the block,
 * so a bed's head never replaces something protected or lands outside the allowed box (PR review).
 */
public final class Footprint {
	private Footprint() {
	}

	public static List<Pos> others(ResolvedBlock block, Pos at) {
		Map<String, List<String>> properties = block.info().properties();
		Map<String, String> state = block.state();
		if (offers(properties, "half", "lower", "upper") && !"upper".equals(state.get("half"))) {
			return List.of(new Pos(at.x(), at.y() + 1, at.z()));
		}
		if (offers(properties, "part", "head", "foot") && !"head".equals(state.get("part"))) {
			// Vanilla's bed faces north unless it's told otherwise.
			return List.of(switch (state.getOrDefault("facing", "north")) {
				case "south" -> new Pos(at.x(), at.y(), at.z() + 1);
				case "west" -> new Pos(at.x() + 1, at.y(), at.z());
				case "east" -> new Pos(at.x() - 1, at.y(), at.z());
				default -> new Pos(at.x(), at.y(), at.z() - 1);
			});
		}
		return List.of();
	}

	private static boolean offers(Map<String, List<String>> properties, String name, String... values) {
		List<String> offered = properties.get(name);
		return offered != null && offered.containsAll(List.of(values));
	}
}
