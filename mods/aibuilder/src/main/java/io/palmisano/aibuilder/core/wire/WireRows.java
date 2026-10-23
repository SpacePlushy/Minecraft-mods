package io.palmisano.aibuilder.core.wire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Pos;

/**
 * A row of wires (spec v2 §5.4), from a row of output ports to a row of input ports along z: each runs along z to a
 * turning row of its own, along x, then along z into its port. Between them it runs at a level (the inputs' height
 * unless given): to it a level a block from its start, and on its last stretch to the inputs' height, so a row given a
 * level 2 or more above another's goes over it.
 * The turning rows are 2 apart from 2 past the outputs; a wire shifting right turns before every wire to its left that
 * also does (rightmost first), and one shifting left before every one to its right, so no two cross or come within a
 * block of each other. Pure.
 */
public final class WireRows {
	private WireRows() {
	}

	/** Each wire's turning row (its z); a wire that doesn't shift goes straight, and gets the first row. Refused with what to change. */
	public static List<Integer> turns(List<Pos> from, List<Pos> to) {
		check(from, to);
		int z0 = from.getFirst().z();
		int z1 = to.getFirst().z();
		int way = Integer.signum(z1 - z0);
		List<Integer> right = new ArrayList<>();
		List<Integer> left = new ArrayList<>();
		for (int i = 0; i < from.size(); i++) {
			if (to.get(i).x() > from.get(i).x()) {
				right.add(i);
			} else if (to.get(i).x() < from.get(i).x()) {
				left.add(i);
			}
		}
		right.sort(Comparator.comparingInt((Integer i) -> from.get(i).x()).reversed());
		left.sort(Comparator.comparingInt((Integer i) -> from.get(i).x()));
		int rows = Math.max(right.size(), left.size());
		if (Math.abs(z1 - z0) < 2 * rows + 2) {
			throw new IllegalArgumentException("the wires that shift sideways turn on rows 2 apart, " + rows + " of them here: the inputs must be at least "
					+ (2 * rows + 2) + " blocks from the outputs along z, not " + Math.abs(z1 - z0));
		}
		List<Integer> turns = new ArrayList<>();
		for (int i = 0; i < from.size(); i++) {
			int row = Math.max(right.indexOf(i), left.indexOf(i));
			turns.add(z0 + way * 2 * (Math.max(row, 0) + 1));
		}
		return turns;
	}

	/** Each wire's spots, from its output port to its input port, at {@code level} between them (null: the inputs' height). */
	public static List<List<Pos>> trace(List<Pos> from, List<Pos> to, Integer level) {
		List<Integer> turns = turns(from, to);
		List<List<Pos>> out = new ArrayList<>();
		for (int i = 0; i < from.size(); i++) {
			Pos a = from.get(i);
			Pos b = to.get(i);
			// Along z to the turning row (straight on if it doesn't shift), along x, along z.
			int turn = a.x() == b.x() ? b.z() : turns.get(i);
			List<int[]> plan = new ArrayList<>(List.of(new int[] {a.x(), a.z()}));
			int x = a.x();
			int z = a.z();
			while (z != turn) {
				z += Integer.signum(turn - z);
				plan.add(new int[] {x, z});
			}
			while (x != b.x()) {
				x += Integer.signum(b.x() - x);
				plan.add(new int[] {x, z});
			}
			while (z != b.z()) {
				z += Integer.signum(b.z() - z);
				plan.add(new int[] {x, z});
			}
			int length = plan.size() - 1;
			if (length > Tunables.WIRE_LENGTH_MAX) {
				throw new IllegalArgumentException("the wire from " + WirePath.spot(a) + " would be " + length + " blocks long, more than " + Tunables.WIRE_LENGTH_MAX);
			}
			int run = level == null ? b.y() : level;
			int up = Math.abs(run - a.y());
			int down = Math.abs(run - b.y());
			if (up + down > length) {
				throw new IllegalArgumentException("the wire from " + WirePath.spot(a) + " to " + WirePath.spot(b) + " would change height " + (up + down)
						+ " levels (to level " + run + ", then to its input) in " + length + " blocks: a wire changes a level a block");
			}
			List<Pos> spots = new ArrayList<>();
			for (int k = 0; k <= length; k++) {
				int y = k <= up ? a.y() + Integer.signum(run - a.y()) * k : k >= length - down ? b.y() + Integer.signum(run - b.y()) * (length - k) : run;
				spots.add(new Pos(plan.get(k)[0], y, plan.get(k)[1]));
			}
			out.add(spots);
		}
		return out;
	}

	private static void check(List<Pos> from, List<Pos> to) {
		if (from.size() != to.size()) {
			throw new IllegalArgumentException("from has " + from.size() + " ports and to has " + to.size() + ": a wire joins one of each");
		}
		if (from.isEmpty() || from.size() > Tunables.WIRE_ROW_MAX) {
			throw new IllegalArgumentException("a call lays 1 to " + Tunables.WIRE_ROW_MAX + " wires, not " + from.size());
		}
		inLine(from, "starts from");
		inLine(to, "ends at");
		if (from.getFirst().z() == to.getFirst().z()) {
			throw new IllegalArgumentException("the inputs are level with the outputs along z (z " + from.getFirst().z() + "): a row of wires runs"
					+ " along z between them, so give one wire via points instead");
		}
		for (int i = 0; i < from.size(); i++) {
			for (int j = i + 1; j < from.size(); j++) {
				if (Integer.signum(from.get(j).x() - from.get(i).x()) != Integer.signum(to.get(j).x() - to.get(i).x())) {
					throw new IllegalArgumentException("the wires from " + WirePath.spot(from.get(i)) + " and " + WirePath.spot(from.get(j))
							+ " would cross: give to in the same order as from");
				}
			}
		}
	}

	private static void inLine(List<Pos> ports, String does) {
		Pos first = ports.getFirst();
		for (Pos p : ports) {
			if (p.y() != first.y() || p.z() != first.z()) {
				throw new IllegalArgumentException("a row of wires " + does + " ports in a row along x (the same y and z): " + WirePath.spot(p)
						+ " isn't in line with " + WirePath.spot(first));
			}
		}
	}
}
