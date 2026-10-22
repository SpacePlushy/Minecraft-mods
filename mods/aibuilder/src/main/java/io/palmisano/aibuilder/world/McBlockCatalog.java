package io.palmisano.aibuilder.world;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.palmisano.aibuilder.core.blocks.BlockCatalog;
import io.palmisano.aibuilder.core.blocks.BlockEntityKind;
import io.palmisano.aibuilder.core.blocks.BlockInfo;
import io.palmisano.aibuilder.core.blocks.PlacementPass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Every registered block, other mods' included, read from the block registry the first time it's needed. */
public final class McBlockCatalog implements BlockCatalog {
	private static volatile McBlockCatalog instance;

	private final Map<String, BlockInfo> blocks;

	private McBlockCatalog(Map<String, BlockInfo> blocks) {
		this.blocks = blocks;
	}

	public static McBlockCatalog get() {
		McBlockCatalog catalog = instance;
		if (catalog == null) {
			synchronized (McBlockCatalog.class) {
				if (instance == null) {
					instance = new McBlockCatalog(read());
				}
				catalog = instance;
			}
		}
		return catalog;
	}

	@Override
	public Optional<BlockInfo> find(String id) {
		return Optional.ofNullable(blocks.get(id));
	}

	@Override
	public Collection<String> ids() {
		return blocks.keySet();
	}

	/** Where a block goes in a batch's placement order (spec §9): supports first, what hangs off them last. */
	public static PlacementPass pass(Block block) {
		if (block instanceof LiquidBlock) {
			return PlacementPass.FLUID;
		}
		if (block instanceof FallingBlock) {
			return PlacementPass.FALLING;
		}
		if (block instanceof DoorBlock || block instanceof AbstractBedBlock || block instanceof LadderBlock
				|| block instanceof LanternBlock || block instanceof CarpetBlock) {
			return PlacementPass.ATTACHED;
		}
		BlockState state = block.defaultBlockState();
		try {
			if (state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
				return PlacementPass.ATTACHED;
			}
			return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) ? PlacementPass.STRUCTURE
					: PlacementPass.PARTIAL;
		} catch (RuntimeException e) {
			// A shape that needs a real world around it: somewhere in the middle.
			return PlacementPass.PARTIAL;
		}
	}

	/** The data place_block_entity can give the block's block entity (spec §8). */
	static BlockEntityKind entityKind(Block block) {
		if (block instanceof SignBlock) {
			return BlockEntityKind.SIGN;
		}
		if (block instanceof AbstractBannerBlock) {
			return BlockEntityKind.BANNER;
		}
		if (block instanceof LecternBlock) {
			return BlockEntityKind.LECTERN;
		}
		if (!(block instanceof EntityBlock entityBlock)) {
			return BlockEntityKind.NONE;
		}
		try {
			BlockEntity entity = entityBlock.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
			return entity instanceof Container container && container.getContainerSize() > 0 ? BlockEntityKind.CONTAINER : BlockEntityKind.OTHER;
		} catch (RuntimeException e) {
			// One that needs a world around it to be made: nothing place_block_entity knows how to fill.
			return BlockEntityKind.OTHER;
		}
	}

	private static Map<String, BlockInfo> read() {
		Map<String, BlockInfo> blocks = new LinkedHashMap<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			String id = BuiltInRegistries.BLOCK.getKey(block).toString();
			Map<String, List<String>> properties = new LinkedHashMap<>();
			for (Property<?> property : block.getStateDefinition().getProperties()) {
				properties.put(property.getName(), valueNames(property));
			}
			boolean unbreakable = block.defaultBlockState().getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) < 0;
			blocks.put(id, new BlockInfo(id, properties, unbreakable, pass(block), block.defaultBlockState().ignitedByLava(), entityKind(block)));
		}
		return Collections.unmodifiableMap(blocks);
	}

	private static <T extends Comparable<T>> List<String> valueNames(Property<T> property) {
		return property.getPossibleValues().stream().map(property::getName).toList();
	}
}
