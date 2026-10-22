package io.palmisano.aibuilder.world;

import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.blocks.ResolvedBlock;
import io.palmisano.aibuilder.core.frame.Facing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Turns a validated block into the block states to write: its properties set, turned from the template (built as
 * if the player faced south) to the build's facing, and the other half of a door, bed or tall plant added.
 */
public final class StateFactory {
	private StateFactory() {
	}

	/** Spec §7: SOUTH none, WEST a clockwise quarter turn, NORTH a half turn, EAST a counterclockwise quarter turn. */
	public static Rotation rotation(Facing facing) {
		return switch (facing) {
			case SOUTH -> Rotation.NONE;
			case WEST -> Rotation.CLOCKWISE_90;
			case NORTH -> Rotation.CLOCKWISE_180;
			case EAST -> Rotation.COUNTERCLOCKWISE_90;
		};
	}

	public static List<Map.Entry<BlockPos, BlockState>> states(ResolvedBlock block, Rotation rotation, BlockPos pos) {
		Block type = BuiltInRegistries.BLOCK.getValue(Identifier.parse(block.info().id()));
		BlockState state = type.defaultBlockState();
		for (Map.Entry<String, String> entry : block.state().entrySet()) {
			Property<?> property = type.getStateDefinition().getProperty(entry.getKey());
			if (property != null) {
				state = withValue(state, property, entry.getValue());
			}
		}
		state = state.rotate(rotation);
		if (type instanceof LeavesBlock) {
			// As a player places them, so a hedge never decays.
			state = state.setValue(LeavesBlock.PERSISTENT, true);
		}
		if (type instanceof DoorBlock && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
			return List.of(Map.entry(pos, state), Map.entry(pos.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));
		}
		if (type instanceof AbstractBedBlock && state.getValue(AbstractBedBlock.PART) == BedPart.FOOT) {
			BlockPos head = pos.relative(state.getValue(HorizontalDirectionalBlock.FACING));
			return List.of(Map.entry(pos, state), Map.entry(head, state.setValue(AbstractBedBlock.PART, BedPart.HEAD)));
		}
		if (type instanceof DoublePlantBlock && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER) {
			return List.of(Map.entry(pos, state), Map.entry(pos.above(), state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER)));
		}
		return List.of(Map.entry(pos, state));
	}

	private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
		return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
	}
}
