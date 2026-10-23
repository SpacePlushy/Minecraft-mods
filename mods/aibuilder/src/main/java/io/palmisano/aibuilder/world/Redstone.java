package io.palmisano.aibuilder.world;

import java.util.List;

import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Which blocks take part in redstone (spec v2 §5.1, §5.5). */
final class Redstone {
	/** What powering changes: it carries the signal on, moves, opens, lights, sounds, fires or goes off. */
	private static final List<Class<? extends Block>> REACTING = List.of(RedstoneWireBlock.class, DiodeBlock.class, RedstoneTorchBlock.class,
			PistonBaseBlock.class, RedstoneLampBlock.class, DoorBlock.class, TrapDoorBlock.class, FenceGateBlock.class, NoteBlock.class,
			DispenserBlock.class, HopperBlock.class, BaseRailBlock.class, CopperBulbBlock.class, CrafterBlock.class, BellBlock.class,
			ObserverBlock.class, TntBlock.class);

	private Redstone() {
	}

	/** A redstone component: it gives power (a lever, a button, a redstone block…) or reacts to it. */
	static boolean component(BlockState state) {
		return state.isSignalSource() || reacts(state);
	}

	/** Something power changes: a door opens, a lamp lights, a piston pushes, TNT goes off. Not a lever or a button. */
	static boolean reacts(BlockState state) {
		Block block = state.getBlock();
		for (Class<? extends Block> kind : REACTING) {
			if (kind.isInstance(block)) {
				return true;
			}
		}
		return false;
	}
}
