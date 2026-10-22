package io.palmisano.aibuilder.world;

import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Whether a block looks like the world made it (spec §11). A heuristic, and documented as one: terrain and growth by
 * vanilla tags plus a short list count as natural; everything else counts as probably player-made, villages and other
 * generated structures included, which keeps builds off them too.
 */
final class ProtectionHeuristic {
	private static final List<TagKey<Block>> NATURAL_TAGS = List.of(BlockTags.BASE_STONE_OVERWORLD, BlockTags.BASE_STONE_NETHER,
			BlockTags.DIRT, BlockTags.SAND, BlockTags.LOGS, BlockTags.LEAVES, BlockTags.FLOWERS, BlockTags.SMALL_FLOWERS, BlockTags.SAPLINGS,
			BlockTags.ICE, BlockTags.SNOW, BlockTags.CORALS, BlockTags.CORAL_BLOCKS, BlockTags.WALL_CORALS, BlockTags.REPLACEABLE,
			BlockTags.REPLACEABLE_BY_TREES, BlockTags.NYLIUM, BlockTags.BADLANDS_TERRACOTTA, BlockTags.CAVE_VINES,
			BlockTags.STONE_ORE_REPLACEABLES, BlockTags.DEEPSLATE_ORE_REPLACEABLES, BlockTags.SCULK_REPLACEABLE, BlockTags.MOSS_REPLACEABLE,
			BlockTags.DRIPSTONE_REPLACEABLE, BlockTags.WART_BLOCKS, ConventionalBlockTags.ORES);

	/** Vanilla blocks the tags miss: more terrain, water plants, cave and Nether growth. */
	private static final Set<String> NATURAL_IDS = Set.of("gravel", "clay", "mud", "obsidian", "crying_obsidian", "sandstone",
			"red_sandstone", "magma_block", "glowstone", "end_stone", "amethyst_block", "budding_amethyst", "small_amethyst_bud",
			"medium_amethyst_bud", "large_amethyst_bud", "amethyst_cluster", "pointed_dripstone", "dripstone_block", "moss_block",
			"moss_carpet", "pale_moss_block", "pale_moss_carpet", "pale_hanging_moss", "sculk", "sculk_vein", "kelp", "kelp_plant",
			"seagrass", "tall_seagrass", "sea_pickle", "sugar_cane", "cactus", "cactus_flower", "bamboo", "vine", "lily_pad",
			"sweet_berry_bush", "brown_mushroom", "red_mushroom", "brown_mushroom_block", "red_mushroom_block", "mushroom_stem",
			"soul_sand", "soul_soil", "shroomlight", "crimson_stem", "warped_stem", "crimson_roots", "warped_roots", "nether_sprouts",
			"weeping_vines", "weeping_vines_plant", "twisting_vines", "twisting_vines_plant", "basalt", "smooth_basalt", "calcite",
			"powder_snow", "glow_lichen", "spore_blossom", "big_dripleaf", "big_dripleaf_stem", "small_dripleaf", "hanging_roots",
			"rooted_dirt", "mangrove_roots", "muddy_mangrove_roots", "chorus_plant", "chorus_flower", "bubble_column", "ancient_debris",
			"dead_bush", "leaf_litter", "firefly_bush", "bush", "short_dry_grass", "tall_dry_grass", "pink_petals", "wildflowers");

	private ProtectionHeuristic() {
	}

	static boolean isNatural(BlockState state) {
		if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
			return true;
		}
		for (TagKey<Block> tag : NATURAL_TAGS) {
			if (state.is(tag)) {
				return true;
			}
		}
		Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		return id.getNamespace().equals("minecraft") && NATURAL_IDS.contains(id.getPath());
	}

	/** Bedrock, barriers, end portal frames…: nothing may replace them. */
	static boolean isUnbreakable(BlockState state) {
		return state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0;
	}
}
