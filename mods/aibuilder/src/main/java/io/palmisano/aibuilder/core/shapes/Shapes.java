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

	/** {@code height} discs along {@code axis}, starting at {@code center}. A disc holds dx² + dz² < (r + ½)². */
	public static List<LocalBlock> cylinder(Pos center, int radius, int height, BlockSpec block, Axis axis, boolean hollow) {
		if (radius < 0 || height < 1) {
			throw new IllegalArgumentException("radius must be at least 0 and height at least 1");
		}
		List<LocalBlock> out = new ArrayList<>();
		for (int k = 0; k < height; k++) {
			for (int a = -radius; a <= radius; a++) {
				for (int b = -radius; b <= radius; b++) {
					if (!inDisc(a, b, radius) || hollow && inDisc(a, b, radius - 1)) {
						continue;
					}
					Pos p = switch (axis) {
						case Y -> new Pos(center.x() + a, center.y() + k, center.z() + b);
						case X -> new Pos(center.x() + k, center.y() + a, center.z() + b);
						case Z -> new Pos(center.x() + a, center.y() + b, center.z() + k);
					};
					out.add(new LocalBlock(p, block));
				}
			}
		}
		return out;
	}

	/** A ball holds dx² + dy² + dz² < (r + ½)²; a dome is its upper half (y ≥ the centre's). */
	public static List<LocalBlock> sphere(Pos center, int radius, BlockSpec block, boolean hollow, boolean dome) {
		if (radius < 0) {
			throw new IllegalArgumentException("radius must be at least 0");
		}
		List<LocalBlock> out = new ArrayList<>();
		for (int dy = dome ? 0 : -radius; dy <= radius; dy++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					if (inBall(dx, dy, dz, radius) && !(hollow && inBall(dx, dy, dz, radius - 1))) {
						out.add(new LocalBlock(new Pos(center.x() + dx, center.y() + dy, center.z() + dz), block));
					}
				}
			}
		}
		return out;
	}

	/** Layer k (0 at the base) is a disc of radius floor(r·(h − k) / h); hollow keeps each layer's ring. */
	public static List<LocalBlock> cone(Pos base, int radius, int height, BlockSpec block, boolean hollow) {
		if (radius < 0 || height < 1) {
			throw new IllegalArgumentException("radius must be at least 0 and height at least 1");
		}
		List<LocalBlock> out = new ArrayList<>();
		for (int k = 0; k < height; k++) {
			int r = radius * (height - k) / height;
			for (int dz = -r; dz <= r; dz++) {
				for (int dx = -r; dx <= r; dx++) {
					if (inDisc(dx, dz, r) && !(hollow && inDisc(dx, dz, r - 1))) {
						out.add(new LocalBlock(new Pos(base.x() + dx, base.y() + k, base.z() + dz), block));
					}
				}
			}
		}
		return out;
	}

	/**
	 * An arch in the vertical plane through {@code from} and {@code to} (same y, and the same x or z), from foot to
	 * foot, {@code rise} high at the middle. The half from {@code from} to the middle is computed and mirrored, so
	 * it's symmetric, and consecutive columns are joined with lines, so it has no gaps. Extra thickness goes to the
	 * right of the from → to direction.
	 */
	public static List<LocalBlock> arch(Pos from, Pos to, int rise, BlockSpec block, int thickness, ArchStyle style) {
		if (from.y() != to.y() || from.x() != to.x() && from.z() != to.z()) {
			throw new IllegalArgumentException("an arch's feet must be level and in a straight line along x or z");
		}
		if (rise < 0 || thickness < 1) {
			throw new IllegalArgumentException("rise must be at least 0 and thickness at least 1");
		}
		boolean alongX = from.z() == to.z();
		int span = alongX ? to.x() - from.x() : to.z() - from.z();
		int step = Integer.signum(span);
		int length = Math.abs(span);
		List<int[]> half = new ArrayList<>();
		for (int u = 0; u <= (length + 1) / 2; u++) {
			int v = (int) Math.round(archHeight(Math.min(u, length - u), length, rise, style));
			if (!half.isEmpty()) {
				int[] previous = half.getLast();
				for (Pos p : linePositions(new Pos(previous[0], previous[1], 0), new Pos(u, v, 0))) {
					half.add(new int[] {p.x(), p.y()});
				}
			} else {
				half.add(new int[] {u, v});
			}
		}
		Set<Pos> curve = new LinkedHashSet<>();
		for (int[] uv : half) {
			curve.add(new Pos(uv[0], uv[1], 0));
			curve.add(new Pos(length - uv[0], uv[1], 0));
		}
		int rightX = alongX ? 0 : step;
		int rightZ = alongX ? -step : 0;
		List<LocalBlock> out = new ArrayList<>();
		for (int layer = 0; layer < thickness; layer++) {
			for (Pos uv : curve) {
				int along = uv.x() * step;
				Pos p = alongX
						? new Pos(from.x() + along + layer * rightX, from.y() + uv.y(), from.z() + layer * rightZ)
						: new Pos(from.x() + layer * rightX, from.y() + uv.y(), from.z() + along + layer * rightZ);
				out.add(new LocalBlock(p, block));
			}
		}
		return out;
	}

	/** Height of the arch at distance {@code u} from the nearer foot (u ≤ length / 2). */
	private static double archHeight(int u, int length, int rise, ArchStyle style) {
		if (length == 0) {
			return rise;
		}
		return switch (style) {
			case ROUND -> {
				double half = length / 2.0;
				double t = (u - half) / half;
				yield rise * Math.sqrt(Math.max(0, 1 - t * t));
			}
			// Two arcs of radius = span, each centred on the opposite foot, scaled so the point is at rise.
			case POINTED -> rise * Math.sqrt(Math.max(0, (double) length * length - (double) (length - u) * (length - u)))
					/ (length * Math.sqrt(3) / 2);
		};
	}

	private static boolean inDisc(int a, int b, int radius) {
		return radius >= 0 && a * a + b * b <= radius * radius + radius;
	}

	private static boolean inBall(int dx, int dy, int dz, int radius) {
		return radius >= 0 && dx * dx + dy * dy + dz * dz <= radius * radius + radius;
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
