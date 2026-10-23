package io.palmisano.aibuilder.core.tools;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import io.palmisano.aibuilder.core.blocks.FakeItems;
import io.palmisano.aibuilder.core.blocks.ItemCatalog;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.FireMask;
import io.palmisano.aibuilder.core.place.ProtectionMask;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.survey.BuildProblems;
import io.palmisano.aibuilder.core.survey.Column;
import io.palmisano.aibuilder.core.survey.TerrainSnapshot;
import io.palmisano.aibuilder.core.wire.Cell;

/** A world for tool tests: grass at y 0 unless told otherwise, a protection mask to set up, fixed facts. */
public final class FakeWorld implements WorldReader {
	public ProtectionMask protection = ProtectionMask.none();
	public FireMask fire = FireMask.none();

	@Override
	public FireMask fire() {
		return fire;
	}
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

	/** The columns as they are now, for terraform; null: the same as {@link #columns}. */
	public BiFunction<Integer, Integer, Column> current;

	@Override
	public TerrainSnapshot currentTerrain(int minX, int minZ, int width, int depth) {
		requests.add("currentTerrain " + minX + " " + minZ + " " + width + " " + depth);
		BiFunction<Integer, Integer, Column> now = current != null ? current : columns;
		List<Column> grid = new ArrayList<>();
		for (int z = minZ; z < minZ + depth; z++) {
			for (int x = minX; x < minX + width; x++) {
				grid.add(now.apply(x, z));
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

	public WorldFacts facts = new WorldFacts("minecraft:overworld", "minecraft:plains", "morning", "clear", "minecraft:grass_block", new Pos(0, 1, -3));

	@Override
	public WorldFacts facts() {
		return facts;
	}

	@Override
	public ProtectionMask protection() {
		return protection;
	}

	public ItemCatalog items = FakeItems.standard();

	@Override
	public ItemCatalog items() {
		return items;
	}

	/** The solid blocks check_motion finds. */
	public Set<Pos> solid = Set.of();

	@Override
	public Set<Pos> solid(Set<Pos> local) {
		requests.add("solid " + local.size());
		Set<Pos> out = new HashSet<>(local);
		out.retainAll(solid);
		return out;
	}

	/** What wire finds where it's set here; elsewhere air above y 0 and ground at and below it, as {@link #states}. */
	public final Map<Pos, Cell> cells = new HashMap<>();

	@Override
	public Map<Pos, Cell> cells(Set<Pos> local) {
		requests.add("cells " + local.size());
		Map<Pos, Cell> out = new HashMap<>();
		for (Pos p : local) {
			out.put(p, cells.getOrDefault(p, p.y() > 0 ? Cell.FREE : Cell.SOLID));
		}
		return out;
	}

	/** What test_redstone answers, and the steps it was asked to run. */
	public String redstone = "after 0 ticks: nothing to read";
	public final List<List<RedstoneSteps.Step>> tested = new ArrayList<>();

	@Override
	public String testRedstone(List<RedstoneSteps.Step> steps) {
		tested.add(steps);
		return redstone;
	}

	/** What check_build finds. */
	public List<BuildProblems.Problem> problems = List.of();

	@Override
	public List<BuildProblems.Problem> check() {
		requests.add("check");
		return problems;
	}
}
