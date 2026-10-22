package io.palmisano.aibuilder.core.survey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import io.palmisano.aibuilder.core.shapes.Box;

/**
 * The survey Claude reads before it builds (spec §11), as compact text: a header, summaries, a palette of surface
 * blocks and two run-length-encoded maps (surface blocks and floor heights), one row per z. When the text would be
 * longer than the cap, both maps use coarser cells (2×2, 3×3, …) until it fits; the summaries always come from
 * every column.
 */
public final class SurveyEncoder {
	/** Keys for surface blocks: letters and symbols, never digits, which are run lengths. */
	private static final String KEYS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ#$%&*+=@^~!<>";
	private static final char OTHER = '?';
	private static final int MAX_CLUSTERS = 20;

	private SurveyEncoder() {
	}

	public static String encode(TerrainSnapshot snapshot, Box allowed, int maxChars) {
		Map<String, Character> palette = palette(snapshot);
		String summary = summary(snapshot, allowed, palette);
		for (int cell = 1; ; cell++) {
			String text = header(snapshot, cell) + summary + maps(snapshot, palette, cell);
			if (text.length() <= maxChars || cell >= Math.max(snapshot.width(), snapshot.depth())) {
				return text;
			}
		}
	}

	private static String header(TerrainSnapshot s, int cell) {
		return "Survey: " + s.width() + "×" + s.depth() + " columns, x " + s.minX() + ".." + s.maxX() + ", z " + s.minZ() + ".." + s.maxZ()
				+ " (local frame: +x right, +z forward), " + cell + "×" + cell + " cells. Heights are y relative to the ground at the origin (y=0).\n";
	}

	private static String summary(TerrainSnapshot s, Box allowed, Map<String, Character> palette) {
		StringBuilder out = new StringBuilder();
		out.append("Allowed box: x ").append(allowed.min().x()).append("..").append(allowed.max().x())
				.append(", y ").append(allowed.min().y()).append("..").append(allowed.max().y())
				.append(", z ").append(allowed.min().z()).append("..").append(allowed.max().z()).append(".\n");

		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		long sum = 0;
		long steps = 0;
		long pairs = 0;
		int water = 0;
		int deepestWater = 0;
		int lava = 0;
		int deepestLava = 0;
		int ceilings = 0;
		int lowestCeiling = Integer.MAX_VALUE;
		for (int z = s.minZ(); z <= s.maxZ(); z++) {
			for (int x = s.minX(); x <= s.maxX(); x++) {
				Column c = s.at(x, z);
				min = Math.min(min, c.floor());
				max = Math.max(max, c.floor());
				sum += c.floor();
				if (x < s.maxX()) {
					steps += Math.abs((long) c.floor() - s.at(x + 1, z).floor());
					pairs++;
				}
				if (z < s.maxZ()) {
					steps += Math.abs((long) c.floor() - s.at(x, z + 1).floor());
					pairs++;
				}
				if (c.water() > 0) {
					water++;
					deepestWater = Math.max(deepestWater, c.water());
				}
				if (c.lava() > 0) {
					lava++;
					deepestLava = Math.max(deepestLava, c.lava());
				}
				if (c.ceiling() != Column.NONE) {
					ceilings++;
					lowestCeiling = Math.min(lowestCeiling, c.ceiling());
				}
			}
		}
		int total = s.columns().size();
		out.append("Floor: ").append(min).append("..").append(max).append(", average ").append(oneDecimal((double) sum / total))
				.append("; slope ").append(oneDecimal(pairs == 0 ? 0 : (double) steps / pairs))
				.append(" (average height step between neighbouring columns).\n");
		out.append(water == 0 ? "Water: none." : "Water: " + water + " of " + total + " columns (" + Math.round(100.0 * water / total)
				+ "%), deepest " + deepestWater + ".");
		out.append(lava == 0 ? " Lava: none." : " Lava: " + lava + (lava == 1 ? " column" : " columns") + ", deepest " + deepestLava + ".");
		out.append(ceilings == 0 ? " Ceilings: none.\n"
				: " Ceilings: over " + ceilings + (ceilings == 1 ? " column" : " columns") + " (cave roof or overhang), lowest at y "
						+ lowestCeiling + ".\n");

		List<List<int[]>> trees = clusters(s, Column::tree);
		out.append("Trees: ");
		if (trees.isEmpty()) {
			out.append("none.\n");
		} else {
			out.append(trees.size()).append(trees.size() == 1 ? " cluster: " : " clusters: ");
			out.append(String.join("; ", trees.stream().limit(MAX_CLUSTERS)
					.map(cells -> box(cells) + " (" + cells.size() + (cells.size() == 1 ? " column)" : " columns)")).toList()));
			out.append(trees.size() > MAX_CLUSTERS ? "; and " + (trees.size() - MAX_CLUSTERS) + " more.\n" : ".\n");
		}

		List<List<int[]>> built = clusters(s, c -> c.protectedCount() > 0);
		out.append("Protected (looks player-made; writes there are refused): ");
		if (built.isEmpty()) {
			out.append("none.\n");
		} else {
			out.append(String.join("; ", built.stream().limit(MAX_CLUSTERS).map(cells -> protectedRegion(s, cells)).toList()));
			out.append(built.size() > MAX_CLUSTERS ? "; and " + (built.size() - MAX_CLUSTERS) + " more.\n" : ".\n");
		}

		out.append("Surface blocks:");
		palette.forEach((block, key) -> out.append(' ').append(key).append('=').append(name(block)));
		if (palette.containsValue(OTHER)) {
			out.append(' ').append(OTHER).append("=other");
		}
		return out.append('\n').toString();
	}

	private static String protectedRegion(TerrainSnapshot s, List<int[]> cells) {
		Map<String, Integer> byBlock = new HashMap<>();
		int count = 0;
		for (int[] cell : cells) {
			Column c = s.at(cell[0], cell[1]);
			count += c.protectedCount();
			byBlock.merge(c.protectedBlock(), c.protectedCount(), Integer::sum);
		}
		String main = byBlock.entrySet().stream()
				.max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
				.map(Map.Entry::getKey).orElse("");
		return box(cells) + ": " + count + (count == 1 ? " block" : " blocks") + ", mostly " + name(main);
	}

	/** Most common floor block first, then by id, each with a key; beyond the keys, "other". */
	private static Map<String, Character> palette(TerrainSnapshot s) {
		Map<String, Integer> counts = new HashMap<>();
		s.columns().forEach(c -> counts.merge(c.floorBlock(), 1, Integer::sum));
		Map<String, Character> palette = new LinkedHashMap<>();
		counts.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.forEach(e -> palette.put(e.getKey(), palette.size() < KEYS.length() ? KEYS.charAt(palette.size()) : OTHER));
		return palette;
	}

	private static String maps(TerrainSnapshot s, Map<String, Character> palette, int cell) {
		StringBuilder surface = new StringBuilder("Surface (rows by z, forward from z ").append(s.minZ()).append("; cells by x, rightward from x ")
				.append(s.minX()).append("; a run like a5 is five cells):\n");
		StringBuilder heights = new StringBuilder("Floor heights (same layout; 3x5 is height 3 five times):\n");
		for (int z = s.minZ(); z <= s.maxZ(); z += cell) {
			List<String> keys = new ArrayList<>();
			List<String> floors = new ArrayList<>();
			for (int x = s.minX(); x <= s.maxX(); x += cell) {
				Map<Character, Integer> keyCounts = new HashMap<>();
				long sum = 0;
				int n = 0;
				for (int dz = 0; dz < cell && z + dz <= s.maxZ(); dz++) {
					for (int dx = 0; dx < cell && x + dx <= s.maxX(); dx++) {
						Column c = s.at(x + dx, z + dz);
						keyCounts.merge(palette.get(c.floorBlock()), 1, Integer::sum);
						sum += c.floor();
						n++;
					}
				}
				char key = keyCounts.entrySet().stream()
						.max(Map.Entry.<Character, Integer>comparingByValue()
								.thenComparing(e -> -KEYS.indexOf(e.getKey())))
						.map(Map.Entry::getKey).orElse(OTHER);
				keys.add(String.valueOf(key));
				floors.add(String.valueOf(Math.round((double) sum / n)));
			}
			surface.append("z ").append(z).append(": ").append(runs(keys, "")).append('\n');
			heights.append("z ").append(z).append(": ").append(runs(floors, "x")).append('\n');
		}
		return surface.append(heights).toString();
	}

	/** "a a a b" → "a3 b"; with a separator, "0 0 2" → "0x2 2". */
	private static String runs(List<String> values, String separator) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < values.size();) {
			int j = i;
			while (j < values.size() && values.get(j).equals(values.get(i))) {
				j++;
			}
			out.add(j - i == 1 ? values.get(i) : values.get(i) + separator + (j - i));
			i = j;
		}
		return String.join(" ", out);
	}

	/** 4-connected groups of columns that match, largest first. */
	private static List<List<int[]>> clusters(TerrainSnapshot s, Predicate<Column> member) {
		boolean[] seen = new boolean[s.width() * s.depth()];
		List<List<int[]>> clusters = new ArrayList<>();
		for (int z = s.minZ(); z <= s.maxZ(); z++) {
			for (int x = s.minX(); x <= s.maxX(); x++) {
				int index = (z - s.minZ()) * s.width() + (x - s.minX());
				if (seen[index] || !member.test(s.at(x, z))) {
					continue;
				}
				List<int[]> cluster = new ArrayList<>();
				Deque<int[]> todo = new ArrayDeque<>();
				todo.add(new int[] {x, z});
				seen[index] = true;
				while (!todo.isEmpty()) {
					int[] cell = todo.poll();
					cluster.add(cell);
					for (int[] step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
						int nx = cell[0] + step[0];
						int nz = cell[1] + step[1];
						if (s.contains(nx, nz)) {
							int next = (nz - s.minZ()) * s.width() + (nx - s.minX());
							if (!seen[next] && member.test(s.at(nx, nz))) {
								seen[next] = true;
								todo.add(new int[] {nx, nz});
							}
						}
					}
				}
				clusters.add(cluster);
			}
		}
		clusters.sort(Comparator.comparingInt((List<int[]> c) -> c.size()).reversed());
		return clusters;
	}

	private static String box(List<int[]> cells) {
		int minX = cells.stream().mapToInt(c -> c[0]).min().orElse(0);
		int maxX = cells.stream().mapToInt(c -> c[0]).max().orElse(0);
		int minZ = cells.stream().mapToInt(c -> c[1]).min().orElse(0);
		int maxZ = cells.stream().mapToInt(c -> c[1]).max().orElse(0);
		return "x " + minX + ".." + maxX + ", z " + minZ + ".." + maxZ;
	}

	/** "minecraft:grass_block" → "grass_block"; other mods keep their namespace. */
	private static String name(String id) {
		return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
	}

	private static String oneDecimal(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}
}
