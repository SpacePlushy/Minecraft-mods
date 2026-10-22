package io.palmisano.aibuilder.core.blocks;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.palmisano.aibuilder.core.frame.Directions;

/** {@code search_blocks} (spec §8): any registered block, other mods' included, with its properties. */
public final class BlockSearch {
	private BlockSearch() {
	}

	/**
	 * Ids containing every word of the query (words split on spaces, {@code _} and {@code :}; "minecraft" ignored),
	 * shortest path first; with no match, the catalog's closest names instead.
	 */
	public static List<BlockInfo> search(BlockCatalog catalog, String query, int limit) {
		List<String> words = Arrays.stream(query.toLowerCase(Locale.ROOT).split("[\\s_:]+"))
				.filter(word -> !word.isEmpty() && !word.equals("minecraft")).toList();
		List<String> matches = words.isEmpty() ? List.of()
				: catalog.ids().stream().filter(id -> words.stream().allMatch(id::contains))
						.sorted(Comparator.comparingInt((String id) -> path(id).length()).thenComparing(Comparator.naturalOrder()))
						.limit(limit).toList();
		if (matches.isEmpty() && !words.isEmpty()) {
			matches = BlockNames.suggest(catalog.ids(), String.join("_", words), limit);
		}
		return matches.stream().map(catalog::find).flatMap(Optional::stream).toList();
	}

	/** "oak_stairs: facing back|forward|right|left; half top|bottom; …", directions as relative words. */
	public static String describe(BlockInfo info) {
		String name = info.id().startsWith("minecraft:") ? path(info.id()) : info.id();
		if (info.properties().isEmpty()) {
			return name;
		}
		return name + ": " + info.properties().entrySet().stream().map(BlockSearch::describe).collect(Collectors.joining("; "));
	}

	private static String describe(Map.Entry<String, List<String>> property) {
		String propertyName = Directions.isCompassToken(property.getKey()) ? Directions.toLocalToken(property.getKey()) : property.getKey();
		return propertyName + " " + property.getValue().stream().map(Directions::toLocalValue).collect(Collectors.joining("|"));
	}

	private static String path(String id) {
		return id.substring(id.indexOf(':') + 1);
	}
}
