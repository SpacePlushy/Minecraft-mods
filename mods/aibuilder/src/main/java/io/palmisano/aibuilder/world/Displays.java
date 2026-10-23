package io.palmisano.aibuilder.world;

import java.util.List;
import java.util.UUID;

import com.mojang.math.Transformation;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.anim.DisplayPose;
import io.palmisano.aibuilder.core.anim.Quat;
import io.palmisano.aibuilder.core.anim.Vec3;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.mixin.BlockDisplayAccessor;
import io.palmisano.aibuilder.mixin.DisplayAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

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

	/** Shows {@code state} instead, if the display is still there: what it showed before, or null when it's gone. */
	static BlockState show(ServerLevel level, UUID id, BlockState state) {
		if (level.getEntity(id) instanceof Display.BlockDisplay display && !display.isRemoved()) {
			BlockState before = ((BlockDisplayAccessor) display).aibuilder$blockState();
			((BlockDisplayAccessor) display).aibuilder$setBlockState(state);
			return before;
		}
		return null;
	}

	/** Tells a display to glide from where it's drawn now to {@code pose}, over {@code ticks}. */
	static void pose(Display.BlockDisplay display, DisplayPose pose, int ticks) {
		DisplayAccessor accessor = (DisplayAccessor) display;
		Vec3 t = pose.translation();
		Quat r = pose.rotation();
		accessor.aibuilder$setTransformation(new Transformation(new Vector3f((float) t.x(), (float) t.y(), (float) t.z()),
				new Quaternionf((float) r.x(), (float) r.y(), (float) r.z(), (float) r.w()), new Vector3f(1, 1, 1), new Quaternionf()));
		accessor.aibuilder$setTransformationInterpolationDuration(ticks);
		accessor.aibuilder$setTransformationInterpolationDelay(0);
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
