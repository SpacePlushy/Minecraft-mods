package io.palmisano.aibuilder.world;

import java.util.List;
import java.util.UUID;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.mixin.BlockDisplayAccessor;
import io.palmisano.aibuilder.mixin.DisplayAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A moving part's blocks (spec v2 §4.1): vanilla block displays, one a block, each showing its block's model in the
 * unit cube from its corner, so at rest a part looks just as its blocks would. They have no collision and change no
 * block. Server thread.
 */
public final class Displays {
	/** On every display a build makes, so one left behind can be found: {@code /kill @e[tag=aibuilder]}. */
	public static final String TAG = "aibuilder";

	private Displays() {
	}

	/** A display of {@code state} at rest at {@code corner}'s block; null when the world won't take it. */
	static Display.BlockDisplay spawn(ServerLevel level, BlockPos corner, BlockState state) {
		Display.BlockDisplay display = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level);
		display.setPos(corner.getX(), corner.getY(), corner.getZ());
		((BlockDisplayAccessor) display).aibuilder$setBlockState(state);
		((DisplayAccessor) display).aibuilder$setViewRange(Tunables.DISPLAY_VIEW_RANGE);
		display.addTag(TAG);
		return level.addFreshEntity(display) ? display : null;
	}

	/** Shows {@code state} instead, if the display is still there; false when it's gone. */
	static boolean show(ServerLevel level, UUID id, BlockState state) {
		if (level.getEntity(id) instanceof Display.BlockDisplay display && !display.isRemoved()) {
			((BlockDisplayAccessor) display).aibuilder$setBlockState(state);
			return true;
		}
		return false;
	}

	/** Takes down the build's displays: they hold nothing of the player's, so all of them go. */
	static void takeDown(ServerLevel level, List<UndoJournal.Spawned> spawned) {
		for (UndoJournal.Spawned s : spawned) {
			if (s.kind().equals(UndoJournal.Spawned.DISPLAY) && level.getEntity(s.id()) instanceof Display.BlockDisplay display) {
				display.discard();
			}
		}
	}
}
