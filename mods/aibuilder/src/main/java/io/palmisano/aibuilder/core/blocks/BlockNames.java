package io.palmisano.aibuilder.core.blocks;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Block id spelling: normalizing, and suggesting real ids for a wrong one. */
public final class BlockNames {
	private BlockNames() {
	}

	/** Lower-case, trimmed, with {@code minecraft:} added when there's no namespace. */
	public static String normalize(String id) {
		String s = id.strip().toLowerCase(Locale.ROOT);
		return s.contains(":") ? s : "minecraft:" + s;
	}

	/** Ids whose path contains the query's (or the reverse) first, then the closest by edit distance. */
	public static List<String> suggest(Collection<String> ids, String query, int max) {
		String q = path(normalize(query));
		Comparator<String> containsFirst = Comparator.comparingInt(id -> path(id).contains(q) || q.contains(path(id)) ? 0 : 1);
		return ids.stream()
				.sorted(containsFirst.thenComparingInt((String id) -> distance(path(id), q)).thenComparing(Comparator.naturalOrder()))
				.limit(max)
				.toList();
	}

	private static String path(String id) {
		return id.substring(id.indexOf(':') + 1);
	}

	private static int distance(String a, String b) {
		int[] previous = new int[b.length() + 1];
		int[] current = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			current[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
				current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[b.length()];
	}
}
