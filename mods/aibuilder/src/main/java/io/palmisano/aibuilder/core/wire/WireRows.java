package io.palmisano.aibuilder.core.wire;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Pos;

/**
 * A row of wires (spec v2 §5.4), from a row of output ports to a row of input ports along z: each runs along z to a
 * turning row of its own, along x, then along z into its port, climbing or dropping a level a block from its start.
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

	/** Each wire's points: its output port, where it turns onto its row and off it (climbing from the start), its input port. */
	public static List<List<Pos>> points(List<Pos> from, List<Pos> to) {
		List<Integer> turns = turns(from, to);
		List<List<Pos>> out = new ArrayList<>();
		for (int i = 0; i < from.size(); i++) {
			Pos a = from.get(i);
			Pos b = to.get(i);
			if (a.x() == b.x()) {
				out.add(List.of(a, b));
				continue;
			}
			int turn = turns.get(i);
			int climb = Math.abs(b.y() - a.y());
			int up = Integer.signum(b.y() - a.y());
			int first = Math.abs(turn - a.z());
			int across = Math.abs(b.x() - a.x());
			out.add(List.of(a, new Pos(a.x(), a.y() + up * Math.min(first, climb), turn), new Pos(b.x(), a.y() + up * Math.min(first + across, climb), turn), b));
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
