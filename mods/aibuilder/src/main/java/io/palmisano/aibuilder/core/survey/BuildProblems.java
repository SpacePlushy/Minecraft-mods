package io.palmisano.aibuilder.core.survey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.palmisano.aibuilder.core.frame.Pos;

/**
 * check_build's answer (spec §8): what the world check found, grouped by kind in a fixed order, local positions,
 * at most so many listed (shared out between the kinds) and the rest counted.
 */
public final class BuildProblems {
	public enum Kind {
		UNSUPPORTED("unsupported", "would break or fall as soon as something next to it changes"),
		DARK("dark", "walkable, under a roof, block light 0: monsters can spawn here; add light"),
		DOOR("door", "can't be walked through: a solid block at head height on a side, or one higher than a step at the feet"),
		LOW("low", "walkable but less than 2 blocks of headroom"),
		SPILL("spill", "fluid beside an open side or over air: it would flow if anything disturbed it"),
		PISTON("piston", "a piston of the build would push or break something that isn't the build's, push slime or honey, or push past"
				+ " the allowed box; change it, or it's taken out when the build ends"),
		POWERS_OUTSIDE("powers_outside", "redstone of the build within 2 blocks of something that reacts to power and isn't the build's (a"
				+ " door, a lamp, a piston, TNT…); move it away, or it's taken out when the build ends");

		private final String label;
		private final String meaning;

		Kind(String label, String meaning) {
			this.label = label;
			this.meaning = meaning;
		}
	}

	/** {@code detail}: a block id, or "". */
	public record Problem(Kind kind, Pos pos, String detail) {
	}

	private static final Comparator<Problem> ORDER = Comparator.comparingInt((Problem p) -> p.pos().y())
			.thenComparingInt(p -> p.pos().z()).thenComparingInt(p -> p.pos().x());

	private BuildProblems() {
	}

	public static String encode(List<Problem> problems, int max) {
		if (problems.isEmpty()) {
			return "No problems found.";
		}
		Map<Kind, List<Problem>> byKind = new EnumMap<>(Kind.class);
		for (Problem p : problems) {
			byKind.computeIfAbsent(p.kind(), k -> new ArrayList<>()).add(p);
		}
		byKind.values().forEach(list -> list.sort(ORDER));
		Map<Kind, Integer> shown = share(byKind, max);
		StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%,d", problems.size()))
				.append(problems.size() == 1 ? " problem" : " problems").append(" (local positions):");
		byKind.forEach((kind, list) -> {
			text.append('\n').append(kind.label).append(" (").append(String.format(Locale.ROOT, "%,d", list.size())).append("), ")
					.append(kind.meaning).append(": ");
			int n = shown.get(kind);
			for (int i = 0; i < n; i++) {
				Problem p = list.get(i);
				text.append(i == 0 ? "" : "; ").append('(').append(p.pos().x()).append(", ").append(p.pos().y()).append(", ")
						.append(p.pos().z()).append(')');
				if (!p.detail().isEmpty()) {
					text.append(' ').append(p.detail().startsWith("minecraft:") ? p.detail().substring("minecraft:".length()) : p.detail());
				}
			}
			if (n < list.size()) {
				text.append(n == 0 ? "" : " ").append("… and ").append(String.format(Locale.ROOT, "%,d", list.size() - n)).append(" more");
			}
		});
		return text.toString();
	}

	/** How many of each kind to list: an even share each, then what the smaller kinds left over, in kind order. */
	private static Map<Kind, Integer> share(Map<Kind, List<Problem>> byKind, int max) {
		Map<Kind, Integer> shown = new EnumMap<>(Kind.class);
		int each = Math.max(1, max / byKind.size());
		int left = max;
		for (Map.Entry<Kind, List<Problem>> e : byKind.entrySet()) {
			int n = Math.min(Math.min(each, e.getValue().size()), Math.max(0, left));
			shown.put(e.getKey(), n);
			left -= n;
		}
		for (Map.Entry<Kind, List<Problem>> e : byKind.entrySet()) {
			int more = Math.min(left, e.getValue().size() - shown.get(e.getKey()));
			shown.merge(e.getKey(), more, Integer::sum);
			left -= more;
		}
		return shown;
	}
}
