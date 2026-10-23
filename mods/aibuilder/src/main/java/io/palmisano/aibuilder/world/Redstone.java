package io.palmisano.aibuilder.world;

import java.util.List;

import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DetectorRailBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TrappedChestBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Which blocks take part in redstone (spec v2 §5.1, §5.5), by what they do with power (M8 review): some give or pass
 * it on by themselves (dust, torches, diodes, levers…), some only react to it (a door, a lamp, a piston), and some
 * give it only when something outside sets them off (a button, a plate, a lectern).
 */
final class Redstone {
	/** What powering changes: it carries the signal on, moves, opens, lights, sounds, fires or goes off. */
	private static final List<Class<? extends Block>> REACTING = List.of(RedstoneWireBlock.class, DiodeBlock.class, RedstoneTorchBlock.class,
			PistonBaseBlock.class, RedstoneLampBlock.class, DoorBlock.class, TrapDoorBlock.class, FenceGateBlock.class, NoteBlock.class,
			DispenserBlock.class, HopperBlock.class, BaseRailBlock.class, CopperBulbBlock.class, CrafterBlock.class, BellBlock.class,
			ObserverBlock.class, TntBlock.class);
	/** Signal sources that give power only when something outside acts on them: a player, a mob, an arrow, lightning, a cart, a sound. */
	private static final List<Class<? extends Block>> SET_OFF_FROM_OUTSIDE = List.of(ButtonBlock.class, BasePressurePlateBlock.class,
			TripWireHookBlock.class, LecternBlock.class, TrappedChestBlock.class, LightningRodBlock.class, TargetBlock.class,
			SculkSensorBlock.class, DetectorRailBlock.class);

	private Redstone() {
	}

	/** A redstone component: it gives power (a lever, a button, a redstone block…) or reacts to it. */
	static boolean component(BlockState state) {
		return state.isSignalSource() || reacts(state);
	}

	/**
	 * Gives or passes on power by itself, as placed or as the machine switches it: dust, torches, repeaters, comparators,
	 * observers, levers, redstone blocks, daylight detectors, powered and activator rails. Not a door or a lamp, which only
	 * react, nor a button or a plate, which only something outside sets off.
	 */
	static boolean emits(BlockState state) {
		Block block = state.getBlock();
		return block instanceof PoweredRailBlock || state.isSignalSource() && !any(SET_OFF_FROM_OUTSIDE, block);
	}

	/** Something power changes: a door opens, a lamp lights, a piston pushes, TNT goes off. Not a lever or a button. */
	static boolean reacts(BlockState state) {
		return any(REACTING, state.getBlock());
	}

	private static boolean any(List<Class<? extends Block>> kinds, Block block) {
		for (Class<? extends Block> kind : kinds) {
			if (kind.isInstance(block)) {
				return true;
			}
		}
		return false;
	}
}
