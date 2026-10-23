package io.palmisano.aibuilder.core.survey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.palmisano.aibuilder.core.shapes.Box;

/**
 * {@code inspect_region}'s text (spec §8): the exact block states in a local box, with a palette, one block of rows
 * per y from the bottom up, one row per z, cells by x. States come in already, directions relative.
 */
public final class RegionEncoder {
	/** No "=": it joins a key to its block in the palette. */
	private static final String KEYS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ#$%&*+@^~!<>?";
	private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

	private RegionEncoder() {
	}

	/** {@code states} in x-fastest, then z, then y order: one per block of the box. */
	public static String encode(Box box, List<String> states) {
		int width = box.max().x() - box.min().x() + 1;
		int height = box.max().y() - box.min().y() + 1;
		int depth = box.max().z() - box.min().z() + 1;
		if (states.size() != width * height * depth) {
			throw new IllegalArgumentException("a " + width + "×" + height + "×" + depth + " region needs " + width * height * depth + " states");
		}
		Map<String, Integer> counts = new HashMap<>();
		states.stream().filter(s -> !AIR.contains(s)).forEach(s -> counts.merge(s, 1, Integer::sum));
		List<String> kinds = new ArrayList<>(counts.keySet());
		kinds.sort(Comparator.comparing((String s) -> -counts.get(s)).thenComparing(Comparator.naturalOrder()));
		int keyWidth = kinds.size() <= KEYS.length() ? 1 : 2;
		Map<String, String> keys = new LinkedHashMap<>();
		for (int i = 0; i < kinds.size(); i++) {
			keys.put(kinds.get(i), keyWidth == 1 ? String.valueOf(KEYS.charAt(i))
					: "" + KEYS.charAt(i / KEYS.length() % KEYS.length()) + KEYS.charAt(i % KEYS.length()));
		}
		String air = ".".repeat(keyWidth);

		StringBuilder out = new StringBuilder("Region x ").append(box.min().x()).append("..").append(box.max().x())
				.append(", y ").append(box.min().y()).append("..").append(box.max().y())
				.append(", z ").append(box.min().z()).append("..").append(box.max().z())
				.append(" (local): ").append(states.size()).append(states.size() == 1 ? " block, " : " blocks, ")
				.append(kinds.size()).append(kinds.size() == 1 ? " kind" : " kinds")
				.append(" besides air. One block of rows per y, bottom up; rows by z, forward; cells by x, rightward; ")
				.append(keyWidth == 1 ? "\".\" is air.\n" : "\"..\" is air, two characters per cell.\n");
		keys.forEach((state, key) -> out.append(key).append('=').append(state.startsWith("minecraft:") ? state.substring(10) : state).append('\n'));
		for (int y = 0; y < height; y++) {
			out.append("y ").append(box.min().y() + y).append(":\n");
			for (int z = 0; z < depth; z++) {
				out.append("z ").append(box.min().z() + z).append(": ");
				for (int x = 0; x < width; x++) {
					String state = states.get((y * depth + z) * width + x);
					out.append(AIR.contains(state) ? air : keys.get(state));
				}
				out.append('\n');
			}
		}
		return out.toString();
	}
}
