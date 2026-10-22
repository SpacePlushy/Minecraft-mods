package io.palmisano.aibuilder.core.survey;

import java.util.List;

/** A grid of columns in the local frame, row-major by z: {@code columns.get((z - minZ) * width + (x - minX))}. */
public record TerrainSnapshot(int minX, int minZ, int width, int depth, List<Column> columns) {
	public TerrainSnapshot {
		if (columns.size() != width * depth) {
			throw new IllegalArgumentException("a " + width + "×" + depth + " snapshot needs " + width * depth + " columns");
		}
		columns = List.copyOf(columns);
	}

	public int maxX() {
		return minX + width - 1;
	}

	public int maxZ() {
		return minZ + depth - 1;
	}

	public boolean contains(int x, int z) {
		return x >= minX && x <= maxX() && z >= minZ && z <= maxZ();
	}

	public Column at(int x, int z) {
		if (!contains(x, z)) {
			throw new IllegalArgumentException("(" + x + ", " + z + ") is outside the snapshot");
		}
		return columns.get((z - minZ) * width + (x - minX));
	}
}
