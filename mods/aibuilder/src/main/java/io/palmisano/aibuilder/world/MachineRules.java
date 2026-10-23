package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.List;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.survey.BuildProblems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a machine may touch (spec v2 §5.5): it moves and powers only the build's own blocks. A piston's push (what
 * vanilla's own resolver says it would move or break, and where each block goes) holds only the build's blocks, inside
 * the allowed box, and never slime or honey, which carry what they touch away; and no redstone of the build is within
 * {@link Tunables#MACHINE_POWER_REACH} blocks of something that reacts to power and isn't the build's. The build's own
 * block is one it journaled that still holds the block it wrote. Reads the world as it stands, loaded chunks only.
 * Server thread.
 */
public final class MachineRules {
	/** A component that breaks a rule: where it is, which rule, and why, in Claude's local positions. */
	record Breaker(BlockPos pos, BuildProblems.Kind kind, String detail) {
	}

	private final ServerLevel level;
	private final LocalFrame frame;
	private final Box allowed;
	private final UndoJournal<BlockState, CompoundTag> journal;

	public MachineRules(ServerLevel level, LocalFrame frame, Box allowed, UndoJournal<BlockState, CompoundTag> journal) {
		this.level = level;
		this.frame = frame;
		this.allowed = allowed;
		this.journal = journal;
	}

	/** The first rule the block at {@code pos} breaks, or null: a piston's push first, then the power rule. */
	Breaker broken(BlockPos pos, BlockState state) {
		if (!Redstone.component(state)) {
			return null;
		}
		String why = state.getBlock() instanceof PistonBaseBlock ? piston(pos, state) : null;
		if (why != null) {
			return new Breaker(pos, BuildProblems.Kind.PISTON, why);
		}
		why = powersOutside(pos, state);
		return why == null ? null : new Breaker(pos, BuildProblems.Kind.POWERS_OUTSIDE, why);
	}

	/** Every rule breaker among the build's positions, as the world stands now. */
	List<Breaker> breakers() {
		List<Breaker> out = new ArrayList<>();
		for (Pos at : journal.positions()) {
			BlockPos pos = Positions.toBlockPos(at);
			if (level.isLoaded(pos)) {
				Breaker breaker = broken(pos, level.getBlockState(pos));
				if (breaker != null) {
					out.add(breaker);
				}
			}
		}
		return out;
	}

	/** Why the piston's push breaks the rule, or null. One that's extended already was looked at before it moved. */
	private String piston(BlockPos pos, BlockState state) {
		if (state.getValue(PistonBaseBlock.EXTENDED)) {
			return null;
		}
		Direction facing = state.getValue(PistonBaseBlock.FACING);
		int reach = Tunables.PISTON_READ_REACH;
		if (!level.hasChunksAt(pos.offset(-reach, 0, -reach), pos.offset(reach, 0, reach))) {
			return name(state) + ": it reaches where the world isn't loaded";
		}
		BlockPos head = pos.relative(facing);
		if (!inBox(head)) {
			return name(state) + ": its head would go to " + local(head) + ", past the allowed box";
		}
		// Slime or honey in its line, even where it couldn't move them now: one push of a block away from a flying machine.
		for (int k = 1; k <= PistonStructureResolver.MAX_PUSH_DEPTH + 1; k++) {
			BlockPos ahead = pos.relative(facing, k);
			BlockState block = level.getBlockState(ahead);
			if (block.isAir()) {
				break;
			}
			if (block.getBlock() instanceof SlimeBlock || block.getBlock() instanceof HoneyBlock) {
				return name(state) + ": it would push " + name(block) + " at " + local(ahead) + ", and slime and honey carry away what they touch";
			}
		}
		PistonStructureResolver push = new PistonStructureResolver(level, pos, facing, true);
		if (!push.resolve()) {
			// Too much to push, or something that can't move: it doesn't move at all.
			return null;
		}
		for (BlockPos moved : push.getToPush()) {
			BlockState block = level.getBlockState(moved);
			if (block.getBlock() instanceof SlimeBlock || block.getBlock() instanceof HoneyBlock) {
				return name(state) + ": it would push " + name(block) + " at " + local(moved) + ", and slime and honey carry away what they touch";
			}
		}
		for (BlockPos moved : push.getToPush()) {
			BlockState block = level.getBlockState(moved);
			if (!owned(moved)) {
				return name(state) + ": it would push " + name(block) + " at " + local(moved) + ", which isn't the build's";
			}
			BlockPos to = moved.relative(facing);
			if (!inBox(to)) {
				return name(state) + ": it would push " + name(block) + " to " + local(to) + ", past the allowed box";
			}
		}
		for (BlockPos broken : push.getToDestroy()) {
			if (!owned(broken) || !inBox(broken)) {
				return name(state) + ": it would break " + name(level.getBlockState(broken)) + " at " + local(broken) + ", which isn't the build's";
			}
		}
		return null;
	}

	/**
	 * The spots a retracted piston's push moves something into or through (its head, each pushed block's next spot,
	 * what it breaks), where a player standing would be pushed; none when it couldn't move, or it's extended.
	 */
	List<BlockPos> reach(BlockPos pos, BlockState state) {
		if (state.getValue(PistonBaseBlock.EXTENDED)) {
			return List.of();
		}
		Direction facing = state.getValue(PistonBaseBlock.FACING);
		int reach = Tunables.PISTON_READ_REACH;
		if (!level.hasChunksAt(pos.offset(-reach, 0, -reach), pos.offset(reach, 0, reach))) {
			return List.of();
		}
		PistonStructureResolver push = new PistonStructureResolver(level, pos, facing, true);
		if (!push.resolve()) {
			return List.of();
		}
		List<BlockPos> out = new ArrayList<>();
		out.add(pos.relative(facing));
		push.getToPush().forEach(moved -> out.add(moved.relative(facing)));
		out.addAll(push.getToDestroy());
		return out;
	}

	/** Why the component could power something that isn't the build's, or null. */
	private String powersOutside(BlockPos pos, BlockState state) {
		int reach = Tunables.MACHINE_POWER_REACH;
		for (BlockPos near : BlockPos.betweenClosed(pos.offset(-reach, -reach, -reach), pos.offset(reach, reach, reach))) {
			if (near.equals(pos) || !level.isLoaded(near)) {
				continue;
			}
			BlockState block = level.getBlockState(near);
			if (Redstone.reacts(block) && !owned(near)) {
				return name(state) + ": it's within " + reach + " blocks of " + name(block) + " at " + local(near) + ", which isn't the build's";
			}
		}
		return null;
	}

	/** The build's own: journaled, and still the block the build wrote there. */
	private boolean owned(BlockPos pos) {
		Pos at = Positions.toPos(pos);
		return journal.has(at) && BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString().equals(journal.writtenBlock(at));
	}

	private boolean inBox(BlockPos pos) {
		return allowed.contains(frame.toLocal(Positions.toPos(pos)));
	}

	private String local(BlockPos pos) {
		Pos p = frame.toLocal(Positions.toPos(pos));
		return "(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
	}

	private static String name(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
	}
}
