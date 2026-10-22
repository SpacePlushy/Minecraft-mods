package io.palmisano.aibuilder.core.blocks;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

/** A few real blocks with their vanilla properties (and whether lava sets them alight), for tests that need a catalog without the game. */
public final class FakeCatalog implements BlockCatalog {
	private static final List<String> FOUR_WAYS = List.of("north", "south", "west", "east");
	private static final List<String> BOOL = List.of("true", "false");
	private static final List<String> LEVELS = IntStream.rangeClosed(0, 15).mapToObj(Integer::toString).toList();

	private final Map<String, BlockInfo> blocks = new LinkedHashMap<>();

	public static FakeCatalog standard() {
		FakeCatalog c = new FakeCatalog();
		c.add("minecraft:air", Map.of(), false, PlacementPass.ATTACHED, false);
		c.add("minecraft:stone", Map.of(), false, PlacementPass.STRUCTURE, false);
		c.add("minecraft:lava", Map.of("level", LEVELS), false, PlacementPass.FLUID, false);
		c.add("minecraft:water", Map.of("level", LEVELS), false, PlacementPass.FLUID, false);
		c.add("minecraft:oak_planks", Map.of(), false, PlacementPass.STRUCTURE, true);
		c.add("minecraft:bedrock", Map.of(), true, PlacementPass.STRUCTURE, false);
		c.add("minecraft:tnt", Map.of("unstable", BOOL), false, PlacementPass.STRUCTURE, true);
		c.add("minecraft:oak_log", Map.of("axis", List.of("x", "y", "z")), false, PlacementPass.STRUCTURE, true);
		c.add("minecraft:oak_stairs", ordered(
				"facing", FOUR_WAYS,
				"half", List.of("top", "bottom"),
				"shape", List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right"),
				"waterlogged", BOOL), false, PlacementPass.PARTIAL, true);
		c.add("minecraft:oak_slab", ordered("type", List.of("top", "bottom", "double"), "waterlogged", BOOL), false, PlacementPass.PARTIAL, true);
		c.add("minecraft:oak_door", ordered(
				"facing", FOUR_WAYS,
				"half", List.of("upper", "lower"),
				"hinge", List.of("left", "right"),
				"open", BOOL,
				"powered", BOOL), false, PlacementPass.ATTACHED, true);
		c.add("minecraft:red_bed", ordered("facing", FOUR_WAYS, "part", List.of("head", "foot"), "occupied", BOOL), false,
				PlacementPass.PARTIAL, true);
		c.add("minecraft:rail", Map.of("shape", List.of("north_south", "east_west", "ascending_east", "ascending_west",
				"ascending_north", "ascending_south", "south_east", "south_west", "north_west", "north_east")), false, PlacementPass.ATTACHED, false);
		c.add("minecraft:vine", ordered("north", BOOL, "east", BOOL, "south", BOOL, "west", BOOL, "up", BOOL), false, PlacementPass.ATTACHED, true);
		c.addEntity("minecraft:oak_sign", ordered("rotation", LEVELS, "waterlogged", BOOL), PlacementPass.ATTACHED, BlockEntityKind.SIGN);
		c.addEntity("minecraft:chest", ordered("facing", FOUR_WAYS, "type", List.of("single", "left", "right"), "waterlogged", BOOL),
				PlacementPass.PARTIAL, BlockEntityKind.CONTAINER);
		c.addEntity("minecraft:lectern", ordered("facing", FOUR_WAYS, "has_book", BOOL, "powered", BOOL), PlacementPass.PARTIAL, BlockEntityKind.LECTERN);
		c.addEntity("minecraft:white_banner", ordered("rotation", LEVELS), PlacementPass.ATTACHED, BlockEntityKind.BANNER);
		return c;
	}

	private void add(String id, Map<String, List<String>> properties, boolean unbreakable, PlacementPass pass, boolean flammable) {
		blocks.put(id, new BlockInfo(id, properties, unbreakable, pass, flammable));
	}

	/** A block with a block entity: all of these burn. */
	private void addEntity(String id, Map<String, List<String>> properties, PlacementPass pass, BlockEntityKind entity) {
		blocks.put(id, new BlockInfo(id, properties, false, pass, true, entity));
	}

	private static Map<String, List<String>> ordered(Object... namesAndValues) {
		Map<String, List<String>> map = new LinkedHashMap<>();
		for (int i = 0; i < namesAndValues.length; i += 2) {
			@SuppressWarnings("unchecked")
			List<String> values = (List<String>) namesAndValues[i + 1];
			map.put((String) namesAndValues[i], values);
		}
		return map;
	}

	@Override
	public Optional<BlockInfo> find(String id) {
		return Optional.ofNullable(blocks.get(id));
	}

	@Override
	public Collection<String> ids() {
		return blocks.keySet();
	}
}
