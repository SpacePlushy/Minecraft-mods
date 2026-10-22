package io.palmisano.aibuilder.core.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.ProtectionMask;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.survey.Column;
import io.palmisano.aibuilder.core.survey.TerrainSnapshot;

/** A world for tool tests: grass at y 0 unless told otherwise, a protection mask to set up, fixed facts. */
public final class FakeWorld implements WorldReader {
	public ProtectionMask protection = ProtectionMask.none();
	/** The column at local (x, z). */
	public BiFunction<Integer, Integer, Column> columns = (x, z) -> new Column(0, 0, Column.NONE, "minecraft:grass_block", 0, 0, false, 0, "");
	/** The state at a local position, for inspect_region. */
	public java.util.function.Function<Pos, String> states = pos -> pos.y() > 0 ? "minecraft:air" : "minecraft:grass_block";
	public final List<String> requests = new ArrayList<>();

	@Override
	public TerrainSnapshot terrain(int minX, int minZ, int width, int depth) {
		requests.add("terrain " + minX + " " + minZ + " " + width + " " + depth);
		List<Column> grid = new ArrayList<>();
		for (int z = minZ; z < minZ + depth; z++) {
			for (int x = minX; x < minX + width; x++) {
				grid.add(columns.apply(x, z));
			}
		}
		return new TerrainSnapshot(minX, minZ, width, depth, grid);
	}

	@Override
	public List<String> states(Box local) {
		requests.add("states " + local);
		List<String> out = new ArrayList<>();
		for (int y = local.min().y(); y <= local.max().y(); y++) {
			for (int z = local.min().z(); z <= local.max().z(); z++) {
				for (int x = local.min().x(); x <= local.max().x(); x++) {
					out.add(states.apply(new Pos(x, y, z)));
				}
			}
		}
		return out;
	}

	@Override
	public WorldFacts facts() {
		return new WorldFacts("minecraft:overworld", "minecraft:plains", "morning", "clear", "minecraft:grass_block", new Pos(0, 1, -3));
	}

	@Override
	public ProtectionMask protection() {
		return protection;
	}
}
