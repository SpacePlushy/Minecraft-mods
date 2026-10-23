package io.palmisano.aibuilder.core.anim;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.Pos;

/**
 * check_motion's answer (spec v2 §4.5). For each moving part: the box it fills as built, and at the start, a quarter,
 * half and three quarters of its longest cycle (its parents' motions included); and the solid blocks it passes
 * through over that cycle, sampled {@link Tunables#CHECK_MOTION_SAMPLES} times, the lowest few listed. Positions are
 * Claude's local ones, and a block is where its centre is.
 */
public final class MotionCheck {
	private static final Comparator<Pos> LOWEST_FIRST = Comparator.comparingInt(Pos::y).thenComparingInt(Pos::x).thenComparingInt(Pos::z);

	private MotionCheck() {
	}

	/**
	 * @param blocks each part's blocks, in the order the parts were made
	 * @param motions each animated part's motion
	 * @param solid which of the positions asked hold a solid block: the world is read once, for every part
	 */
	public static String report(Map<String, List<Pos>> blocks, Map<String, MovingPart> motions, Facing facing, Function<Set<Pos>, Set<Pos>> solid) {
		if (blocks.isEmpty()) {
			return "This build has no moving parts: give a write tool a part name to make one, then animate it.";
		}
		Map<String, Set<Pos>> swept = new LinkedHashMap<>();
		Set<Pos> asked = new HashSet<>();
		blocks.forEach((name, list) -> {
			int period = longestPeriod(name, motions);
			Set<Pos> through = new HashSet<>(list);
			for (int s = 1; period > 0 && s < Tunables.CHECK_MOTION_SAMPLES; s++) {
				Rigid pose = Poses.rigid(name, motions, (double) s * period / Tunables.CHECK_MOTION_SAMPLES, facing);
				list.forEach(block -> through.add(Poses.moved(pose, block, facing)));
			}
			// The first sample is the cycle's start, which may not be as built (a swing that starts at its from).
			Rigid start = Poses.rigid(name, motions, 0, facing);
			list.forEach(block -> through.add(Poses.moved(start, block, facing)));
			swept.put(name, through);
			asked.addAll(through);
		});
		Set<Pos> found = solid.apply(asked);
		StringBuilder out = new StringBuilder("Each moving part, in your positions (a box from its lowest corner to its highest), over its longest cycle:");
		blocks.forEach((name, list) -> {
			MovingPart motion = motions.get(name);
			int period = longestPeriod(name, motions);
			out.append("\n- ").append(name).append(" (").append(count(list.size(), "block"));
			if (motion != null && !motion.parent().isEmpty()) {
				out.append(", riding ").append(motion.parent());
			}
			out.append("): ");
			if (period == 0) {
				out.append("stands still (animate gives it a motion); ");
			}
			out.append("as built ").append(box(list));
			for (int quarter = 0; period > 0 && quarter < 4; quarter++) {
				double ticks = quarter * period / 4.0;
				Rigid pose = Poses.rigid(name, motions, ticks, facing);
				out.append("; at ").append(ticks(ticks)).append(" ticks ").append(box(list.stream().map(b -> Poses.moved(pose, b, facing)).toList()));
			}
			List<Pos> hits = swept.get(name).stream().filter(found::contains).sorted(LOWEST_FIRST).toList();
			String verb = period == 0 ? "is inside" : "passes through";
			if (hits.isEmpty()) {
				out.append("; ").append(verb).append(" no solid block.");
			} else {
				out.append("; ").append(verb).append(' ').append(count(hits.size(), "solid block")).append(": ");
				out.append(String.join(", ", hits.stream().limit(Tunables.CHECK_MOTION_LISTED).map(MotionCheck::pos).toList()));
				if (hits.size() > Tunables.CHECK_MOTION_LISTED) {
					out.append(" and ").append(hits.size() - Tunables.CHECK_MOTION_LISTED).append(" more");
				}
				out.append('.');
			}
		});
		return out.toString();
	}

	/** The longest cycle among the part's motions and its parents'; 0 when it stands still. */
	private static int longestPeriod(String part, Map<String, MovingPart> motions) {
		int longest = 0;
		String at = part;
		for (int depth = 0; !at.isEmpty() && depth <= Tunables.PART_DEPTH_MAX; depth++) {
			MovingPart p = motions.get(at);
			if (p == null) {
				break;
			}
			for (Motion m : p.motions()) {
				longest = Math.max(longest, Math.abs(switch (m) {
					case Motion.Swing s -> s.period();
					case Motion.Spin s -> s.period();
					case Motion.Bob b -> b.period();
				}));
			}
			at = p.parent();
		}
		return longest;
	}

	private static String box(List<Pos> blocks) {
		Pos min = new Pos(blocks.stream().mapToInt(Pos::x).min().orElse(0), blocks.stream().mapToInt(Pos::y).min().orElse(0),
				blocks.stream().mapToInt(Pos::z).min().orElse(0));
		Pos max = new Pos(blocks.stream().mapToInt(Pos::x).max().orElse(0), blocks.stream().mapToInt(Pos::y).max().orElse(0),
				blocks.stream().mapToInt(Pos::z).max().orElse(0));
		return pos(min) + " to " + pos(max);
	}

	private static String pos(Pos p) {
		return "[" + p.x() + ", " + p.y() + ", " + p.z() + "]";
	}

	private static String ticks(double ticks) {
		return ticks == Math.rint(ticks) ? Long.toString((long) ticks) : String.format(Locale.ROOT, "%.1f", ticks);
	}

	private static String count(int n, String what) {
		return String.format(Locale.ROOT, "%,d", n) + " " + what + (n == 1 ? "" : "s");
	}
}
