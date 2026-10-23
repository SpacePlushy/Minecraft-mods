package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * What a machine may touch (spec v2 §5.5): it moves and powers only the build's own blocks. A piston's push (what
 * vanilla's own resolver says it would move or break, and where each block goes) and a sticky piston's pull hold only
 * the build's blocks, inside the allowed box, and never slime or honey, which carry what they touch away; nothing of the
 * build that gives power by itself (dust, torches, diodes, levers…; not a door or a button, M8 review) is within
 * {@link Tunables#MACHINE_POWER_REACH} blocks of something that reacts to power and isn't the build's; and no hopper,
 * dropper or crafter of the build feeds a dispenser, nor one that isn't the build's (an earlier build's, the player's:
 * it may hold anything) a dispenser of the build, which would fire what the dispenser rule keeps out of it. The
 * build's own block is one it journaled that still holds the block it wrote. A piece the power-up pass has started was
 * judged as placed, before it ran, and isn't judged again: the machine may have moved the build's blocks since. Reads
 * the world as it stands, loaded chunks only. Server thread.
 */
public final class MachineRules {
	/** A component that breaks a rule: where it is, which rule, and why, in Claude's local positions. */
	record Breaker(BlockPos pos, BuildProblems.Kind kind, String detail) {
	}

	private final ServerLevel level;
	private final LocalFrame frame;
	private final Box allowed;
	private final UndoJournal<BlockState, CompoundTag> journal;
	/** The player's earlier builds here (a refinement's session among them): their blocks count as the build's own. */
	private final List<UndoJournal<BlockState, CompoundTag>> earlier;
	/** The positions the power-up pass has started (the job's record): judged as placed, before they ran. */
	private Set<BlockPos> started = Set.of();

	public MachineRules(ServerLevel level, LocalFrame frame, Box allowed, UndoJournal<BlockState, CompoundTag> journal,
			List<UndoJournal<BlockState, CompoundTag>> earlier) {
		this.level = level;
		this.frame = frame;
		this.allowed = allowed;
		this.journal = journal;
		this.earlier = List.copyOf(earlier);
	}

	/** The job's record of what the power-up pass has started. Before any check. */
	void started(Set<BlockPos> started) {
		this.started = started;
	}

	/** The first rule the block at {@code pos} breaks, or null: a piston's push or pull, a feeder, then the power rule. */
	Breaker broken(BlockPos pos, BlockState state) {
		if (started.contains(pos)) {
			return null;
		}
		if (state.getBlock() instanceof PistonBaseBlock) {
			String why = piston(pos, state);
			return why == null ? null : new Breaker(pos, BuildProblems.Kind.PISTON, why);
		}
		String fed = feeds(pos, state);
		if (fed != null) {
			return new Breaker(pos, BuildProblems.Kind.FEEDS_DISPENSER, fed);
		}
		if (!Redstone.emits(state)) {
			return null;
		}
		String near = reactingNear(pos);
		return near == null ? null : new Breaker(pos, BuildProblems.Kind.POWERS_OUTSIDE, name(state) + ": it's within "
				+ Tunables.MACHINE_POWER_REACH + " blocks of " + near + ", which isn't the build's");
	}

	/** Every rule breaker among the build's positions not started yet, as the world stands now. */
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

	/** Why the piston breaks the rule, or null. One out already was looked at before it moved; a sticky one still pulls. */
	private String piston(BlockPos pos, BlockState state) {
		Direction facing = state.getValue(PistonBaseBlock.FACING);
		boolean sticky = state.is(Blocks.STICKY_PISTON);
		int reach = Tunables.PISTON_READ_REACH;
		if (!level.hasChunksAt(pos.offset(-reach, 0, -reach), pos.offset(reach, 0, reach))) {
			return name(state) + ": it reaches where the world isn't loaded";
		}
		if (state.getValue(PistonBaseBlock.EXTENDED)) {
			return sticky ? pull(state, pos, facing) : null;
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
		// Nothing before it to push: the head goes out, and coming back a sticky piston pulls what's then before its head.
		return sticky && push.getToPush().isEmpty() ? pull(state, pos, facing) : null;
	}

	/** Why the block two ahead of a sticky piston, which its head pulls back, breaks the rule; null if it's air, won't move, or is the build's own. */
	private String pull(BlockState piston, BlockPos pos, Direction facing) {
		BlockPos spot = pos.relative(facing, 2);
		BlockState block = level.getBlockState(spot);
		if (block.isAir() || !PistonBaseBlock.isPushable(block, level, spot, facing.getOpposite(), false, facing)) {
			return null;
		}
		if (block.getBlock() instanceof SlimeBlock || block.getBlock() instanceof HoneyBlock) {
			return name(piston) + ": it would pull " + name(block) + " at " + local(spot) + ", and slime and honey carry away what they touch";
		}
		if (!owned(spot) || !inBox(spot)) {
			return name(piston) + ": it would pull " + name(block) + " at " + local(spot) + ", which isn't the build's";
		}
		return null;
	}

	/**
	 * Why the build's block feeds a dispenser what the dispenser rule keeps out, or null: a hopper, dropper or crafter
	 * facing into one, or a dispenser one that isn't the build's faces into (the build's own are judged where they stand).
	 */
	private String feeds(BlockPos pos, BlockState state) {
		if (state.getBlock() instanceof DispenserBlock && !(state.getBlock() instanceof DropperBlock)) {
			for (Direction side : Direction.values()) {
				BlockPos from = pos.relative(side);
				if (!level.isLoaded(from) || journal.has(Positions.toPos(from))) {
					continue;
				}
				BlockState feeder = level.getBlockState(from);
				Direction out = output(feeder);
				if (out != null && from.relative(out).equals(pos)) {
					return name(state) + ": the " + name(feeder) + " at " + local(from) + ", not this build's, faces into it, so it would fire what it's fed";
				}
			}
			return null;
		}
		Direction out = output(state);
		if (out == null) {
			return null;
		}
		BlockPos into = pos.relative(out);
		if (!level.isLoaded(into)) {
			return null;
		}
		BlockState target = level.getBlockState(into);
		return target.getBlock() instanceof DispenserBlock && !(target.getBlock() instanceof DropperBlock)
				? name(state) + ": it faces into the dispenser at " + local(into) + ", which would fire what it's fed"
				: null;
	}

	/** The side a hopper, dropper or crafter gives out of, or null for any other block. */
	private static Direction output(BlockState state) {
		if (state.getBlock() instanceof HopperBlock) {
			return state.getValue(HopperBlock.FACING);
		}
		if (state.getBlock() instanceof DropperBlock) {
			return state.getValue(DispenserBlock.FACING);
		}
		if (state.getBlock() instanceof CrafterBlock && state.hasProperty(BlockStateProperties.ORIENTATION)) {
			return state.getValue(BlockStateProperties.ORIENTATION).front();
		}
		return null;
	}

	/**
	 * The spots a retracted piston's push moves something into or through (its head, each pushed block's next spot,
	 * what it breaks), where a player standing would be pushed; none when it couldn't move, or it's extended.
	 */
	List<BlockPos> reach(BlockPos pos, BlockState state) {
		return pushReach(level, pos, state);
	}

	/** {@link #reach}, for a piston anywhere. */
	static List<BlockPos> pushReach(Level level, BlockPos pos, BlockState state) {
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

	/** The pistons of the build and of the player's earlier builds here, as they stand now: any may be driven by a change. */
	List<BlockPos> pistons() {
		List<BlockPos> out = new ArrayList<>();
		addPistons(level, journal, out);
		for (UndoJournal<BlockState, CompoundTag> build : earlier) {
			addPistons(level, build, out);
		}
		return out;
	}

	/** The journal's positions written as a piston that still hold one. */
	static void addPistons(Level level, UndoJournal<BlockState, CompoundTag> journal, List<BlockPos> out) {
		for (Pos at : journal.positions()) {
			String written = journal.writtenBlock(at);
			BlockPos pos = Positions.toBlockPos(at);
			if (written != null && written.endsWith("piston") && level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof PistonBaseBlock) {
				out.add(pos);
			}
		}
	}

	/** The first of {@code pistons} whose push would reach where a player stands, or null: one change could move them. */
	static BlockPos playerInReach(Level level, List<BlockPos> pistons, Occupancy occupancy) {
		for (BlockPos pos : pistons) {
			if (!level.isLoaded(pos)) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof PistonBaseBlock && pushReach(level, pos, state).stream().anyMatch(occupancy::occupied)) {
				return pos;
			}
		}
		return null;
	}

	/** The first block within reach of {@code pos} that reacts to power and isn't the build's, as "oak_door at (2, 1, 4)"; null if none. */
	String reactingNear(BlockPos pos) {
		int reach = Tunables.MACHINE_POWER_REACH;
		for (BlockPos near : BlockPos.betweenClosed(pos.offset(-reach, -reach, -reach), pos.offset(reach, reach, reach))) {
			if (near.equals(pos) || !level.isLoaded(near)) {
				continue;
			}
			BlockState block = level.getBlockState(near);
			if (Redstone.reacts(block) && !owned(near)) {
				return name(block) + " at " + local(near);
			}
		}
		return null;
	}

	/** The build's own: journaled by it or an earlier build of the player's here, and still the block written there. */
	boolean owned(BlockPos pos) {
		Pos at = Positions.toPos(pos);
		String now = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
		if (journal.has(at) && now.equals(journal.writtenBlock(at))) {
			return true;
		}
		for (UndoJournal<BlockState, CompoundTag> build : earlier) {
			if (build.has(at) && now.equals(build.writtenBlock(at))) {
				return true;
			}
		}
		return false;
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
