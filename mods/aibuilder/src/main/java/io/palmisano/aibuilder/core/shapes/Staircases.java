package io.palmisano.aibuilder.core.shapes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;

/** Staircases from stair blocks; each step faces the way you walk up it. */
public final class Staircases {
	private static final Map<String, int[]> STEPS = Map.of("forward", new int[] {0, 1}, "back", new int[] {0, -1},
			"right", new int[] {1, 0}, "left", new int[] {-1, 0});

	private Staircases() {
	}

	/** Step i at {@code from + i·direction + i·up}; extra width goes to the right of the direction. */
	public static List<LocalBlock> straight(Pos from, String direction, int steps, int width, BlockSpec stairs) {
		int[] d = STEPS.get(direction);
		if (d == null) {
			throw new IllegalArgumentException("direction must be forward, back, left or right");
		}
		if (steps < 1 || width < 1) {
			throw new IllegalArgumentException("steps and width must be at least 1");
		}
		BlockSpec step = facing(stairs, direction);
		List<LocalBlock> out = new ArrayList<>();
		for (int i = 0; i < steps; i++) {
			for (int w = 0; w < width; w++) {
				out.add(new LocalBlock(new Pos(from.x() + d[0] * i + d[1] * w, from.y() + i, from.z() + d[1] * i - d[0] * w), step));
			}
		}
		return out;
	}

	/**
	 * One step per level from {@code fromY} to {@code toY}, round the ring of cells {@code radius} (Chebyshev) from the
	 * centre column, which is left for a pillar. It starts straight back of the centre; clockwise (seen from above)
	 * goes back → left → forward → right.
	 */
	public static List<LocalBlock> spiral(Pos center, int radius, int fromY, int toY, BlockSpec stairs, boolean clockwise) {
		if (radius < 1) {
			throw new IllegalArgumentException("radius must be at least 1");
		}
		if (toY < fromY) {
			throw new IllegalArgumentException("to_y must not be below from_y");
		}
		List<int[]> ring = ring(radius, clockwise);
		List<LocalBlock> out = new ArrayList<>();
		for (int s = 0; s <= toY - fromY; s++) {
			int[] cell = ring.get(s % ring.size());
			int[] next = ring.get((s + 1) % ring.size());
			String word = word(next[0] - cell[0], next[1] - cell[1]);
			out.add(new LocalBlock(new Pos(center.x() + cell[0], fromY + s, center.z() + cell[1]), facing(stairs, word)));
		}
		return out;
	}

	private static List<int[]> ring(int r, boolean clockwise) {
		int side = clockwise ? -1 : 1;
		int[][] legs = {{side, 0, r}, {0, 1, 2 * r}, {-side, 0, 2 * r}, {0, -1, 2 * r}, {side, 0, r - 1}};
		List<int[]> cells = new ArrayList<>();
		int x = 0;
		int z = -r;
		cells.add(new int[] {x, z});
		for (int[] leg : legs) {
			for (int i = 0; i < leg[2]; i++) {
				x += leg[0];
				z += leg[1];
				cells.add(new int[] {x, z});
			}
		}
		return cells;
	}

	private static String word(int dx, int dz) {
		if (dx > 0) {
			return "right";
		}
		if (dx < 0) {
			return "left";
		}
		return dz > 0 ? "forward" : "back";
	}

	private static BlockSpec facing(BlockSpec stairs, String direction) {
		return new BlockSpec(stairs.id(), Map.of("facing", direction, "half", "bottom"));
	}
}
