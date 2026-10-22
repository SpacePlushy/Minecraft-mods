package io.palmisano.aibuilder.core.shapes;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;

/** The geometry primitives, in the local frame; each returns the blocks to place, bottom-up. */
public final class Shapes {
	static final BlockSpec AIR = BlockSpec.of("minecraft:air");

	private Shapes() {
	}

	public static List<LocalBlock> fill(Box box, BlockSpec block, FillMode mode) {
		List<LocalBlock> out = new ArrayList<>();
		Pos min = box.min();
		Pos max = box.max();
		for (int y = min.y(); y <= max.y(); y++) {
			for (int z = min.z(); z <= max.z(); z++) {
				for (int x = min.x(); x <= max.x(); x++) {
					boolean shell = x == min.x() || x == max.x() || y == min.y() || y == max.y() || z == min.z() || z == max.z();
					Pos p = new Pos(x, y, z);
					switch (mode) {
						case SOLID -> out.add(new LocalBlock(p, block));
						case HOLLOW -> out.add(new LocalBlock(p, shell ? block : AIR));
						case OUTLINE -> {
							if (shell) {
								out.add(new LocalBlock(p, block));
							}
						}
					}
				}
			}
		}
		return out;
	}

	/** Every position of the box, placed only where the current block matches {@code filter}. */
	public static List<LocalBlock> replace(Box box, BlockSpec block, String filter) {
		return fill(box, block, FillMode.SOLID).stream().map(b -> new LocalBlock(b.pos(), block, filter)).toList();
	}

	/** Both ends included, no position twice. */
	public static List<LocalBlock> line(Pos from, Pos to, BlockSpec block) {
		return linePositions(from, to).stream().map(p -> new LocalBlock(p, block)).toList();
	}

	/**
	 * A vertical wall along a polyline of {@code [x, z]} points, {@code height} blocks tall from {@code y}. Each
	 * extra unit of thickness is a copy one block to the right of the walking direction.
	 */
	public static List<LocalBlock> wall(List<int[]> points, int y, int height, BlockSpec block, int thickness) {
		if (points.isEmpty()) {
			throw new IllegalArgumentException("a wall needs at least one point");
		}
		if (height < 1 || thickness < 1) {
			throw new IllegalArgumentException("height and thickness must be at least 1");
		}
		Set<Pos> columns = new LinkedHashSet<>();
		if (points.size() == 1) {
			columns.add(new Pos(points.getFirst()[0], 0, points.getFirst()[1]));
		}
		for (int i = 0; i + 1 < points.size(); i++) {
			int[] a = points.get(i);
			int[] b = points.get(i + 1);
			int rightX = Integer.signum(b[1] - a[1]);
			int rightZ = -Integer.signum(b[0] - a[0]);
			for (int layer = 0; layer < thickness; layer++) {
				Pos from = new Pos(a[0] + layer * rightX, 0, a[1] + layer * rightZ);
				Pos to = new Pos(b[0] + layer * rightX, 0, b[1] + layer * rightZ);
				columns.addAll(linePositions(from, to));
			}
		}
		List<LocalBlock> out = new ArrayList<>();
		for (int dy = 0; dy < height; dy++) {
			for (Pos column : columns) {
				out.add(new LocalBlock(new Pos(column.x(), y + dy, column.z()), block));
			}
		}
		return out;
	}

	static List<Pos> linePositions(Pos from, Pos to) {
		int dx = to.x() - from.x();
		int dy = to.y() - from.y();
		int dz = to.z() - from.z();
		int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
		Set<Pos> out = new LinkedHashSet<>();
		for (int i = 0; i <= steps; i++) {
			double t = steps == 0 ? 0 : (double) i / steps;
			out.add(new Pos(from.x() + (int) Math.round(dx * t), from.y() + (int) Math.round(dy * t), from.z() + (int) Math.round(dz * t)));
		}
		return List.copyOf(out);
	}
}
