package io.palmisano.aibuilder.core.wire;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;

/**
 * A wire's way (spec v2 §5.4): the spots its dust takes from its output port to its input port, where it needs
 * repeaters, and its blocks. From each point to the next it goes along z, then along x, a level a block from the leg's
 * start until it's at the next point's height. Pure.
 */
public final class WirePath {
	/** Dust next to a repeater (or a port's buffer) is at 15 and loses one a block; at 0 it's off. */
	private static final int FULL = 15;
	private static final BlockSpec DUST = BlockSpec.of("redstone_wire");
	private static final BlockSpec STONE = BlockSpec.of("stone");

	private WirePath() {
	}

	/** The spots from the first point to the last, each a block from the one before (and a level, on a slope). */
	public static List<Pos> trace(List<Pos> points) {
		int length = 0;
		for (int i = 1; i < points.size(); i++) {
			Pos a = points.get(i - 1);
			Pos b = points.get(i);
			if (a.equals(b)) {
				throw new IllegalArgumentException("the wire's points " + spot(a) + " and " + spot(b) + " are the same spot");
			}
			int leg = Math.abs(b.x() - a.x()) + Math.abs(b.z() - a.z());
			int rise = Math.abs(b.y() - a.y());
			if (rise > leg) {
				String way = b.y() > a.y() ? "climb" : "drop";
				throw new IllegalArgumentException("from " + spot(a) + " to " + spot(b) + " the wire would " + way + " " + rise + (rise == 1 ? " level" : " levels")
						+ " in " + leg + (leg == 1 ? " block" : " blocks") + ": it " + way + "s a level a block, so give it room (a via point further off)");
			}
			length += leg;
		}
		if (length > Tunables.WIRE_LENGTH_MAX) {
			throw new IllegalArgumentException("the wire would be " + length + " blocks long, more than " + Tunables.WIRE_LENGTH_MAX);
		}
		List<Pos> spots = new ArrayList<>(List.of(points.getFirst()));
		for (int i = 1; i < points.size(); i++) {
			Pos b = points.get(i);
			Pos at = spots.getLast();
			int x = at.x();
			int y = at.y();
			int z = at.z();
			while (z != b.z()) {
				z += Integer.signum(b.z() - z);
				y += Integer.signum(b.y() - y);
				spots.add(new Pos(x, y, z));
			}
			while (x != b.x()) {
				x += Integer.signum(b.x() - x);
				y += Integer.signum(b.y() - y);
				spots.add(new Pos(x, y, z));
			}
		}
		return spots;
	}

	/**
	 * The spots (by index) that take a repeater so the signal reaches the last at 1 or more: where a dust would be at 0,
	 * the last spot before it on a straight, level stretch (its spots before and after level with it and in line), never
	 * an end, a turn or a slope. Refused when there's no such spot in time.
	 */
	public static Set<Integer> repeaters(List<Pos> spots) {
		Set<Integer> out = new TreeSet<>();
		int full = 0;
		int free = -1;
		for (int k = 1; k < spots.size(); k++) {
			if (FULL - (k - full) < 1) {
				if (free < 0) {
					throw new IllegalArgumentException("the signal would run out at " + spot(spots.get(k)) + ", with no straight, level stretch before it since "
							+ spot(spots.get(full)) + " for a repeater: give the wire a level run (via points)");
				}
				out.add(free);
				full = free + 1;
				free = -1;
				for (int j = full; j < k; j++) {
					if (takes(spots, j)) {
						free = j;
					}
				}
			}
			if (takes(spots, k)) {
				free = k;
			}
		}
		return out;
	}

	/** Whether spot {@code k} can be a repeater: not an end, level with the spots either side and in line with them. */
	private static boolean takes(List<Pos> spots, int k) {
		if (k <= 0 || k >= spots.size() - 1) {
			return false;
		}
		Pos a = spots.get(k - 1);
		Pos b = spots.get(k);
		Pos c = spots.get(k + 1);
		return a.y() == b.y() && b.y() == c.y() && b.x() - a.x() == c.x() - b.x() && b.z() - a.z() == c.z() - b.z();
	}

	/** Dust on each spot, a repeater carrying the signal on at each of {@code repeaters}, and stone at each of {@code supports}. */
	public static List<LocalBlock> blocks(List<Pos> spots, Set<Integer> repeaters, Set<Pos> supports) {
		List<LocalBlock> out = new ArrayList<>();
		for (Pos support : supports) {
			out.add(new LocalBlock(support, STONE));
		}
		for (int k = 0; k < spots.size(); k++) {
			out.add(new LocalBlock(spots.get(k), repeaters.contains(k) ? repeater(spots.get(k - 1), spots.get(k)) : DUST));
		}
		return out;
	}

	/** A repeater the signal reaches from {@code from}: its facing names its input side (26.3), back toward {@code from}. */
	private static BlockSpec repeater(Pos from, Pos at) {
		String input = at.x() > from.x() ? "left" : at.x() < from.x() ? "right" : at.z() > from.z() ? "back" : "forward";
		return new BlockSpec("repeater", Map.of("facing", input, "delay", "1"));
	}

	static String spot(Pos p) {
		return "(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
	}
}
