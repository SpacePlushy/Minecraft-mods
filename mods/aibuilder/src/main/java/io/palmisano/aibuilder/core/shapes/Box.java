package io.palmisano.aibuilder.core.shapes;

import io.palmisano.aibuilder.core.frame.Pos;

/** An inclusive box of block positions. */
public record Box(Pos min, Pos max) {
	/** Corners in any order. */
	public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
		return new Box(new Pos(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2)),
				new Pos(Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)));
	}

	public boolean contains(Pos p) {
		return p.x() >= min.x() && p.x() <= max.x() && p.y() >= min.y() && p.y() <= max.y() && p.z() >= min.z() && p.z() <= max.z();
	}

	public long volume() {
		return (long) (max.x() - min.x() + 1) * (max.y() - min.y() + 1) * (max.z() - min.z() + 1);
	}
}
