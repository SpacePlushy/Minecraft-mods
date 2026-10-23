package io.palmisano.aibuilder.world;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.palmisano.aibuilder.core.anim.MovingPart;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.core.tools.PartInfo;
import io.palmisano.aibuilder.mixin.BlockDisplayAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One build's moving parts into an {@link AnimationStore} as they're made (spec v2 §4.4): each display its placement
 * job makes and each motion animate gives, so a part moves as soon as it has both, while the build goes on. A new
 * build's motion counts from the game time it began, and the store hears of it once it has a part. A refinement joins
 * the moving build it refines, keeping its clock; it may change the earlier parts, and its journal keeps what they had
 * (the motion, the blocks its displays showed), so undo gives it back.
 */
public final class MovingParts {
	private final ServerLevel level;
	private final AnimationStore store;
	private final AnimationStore.Key key;
	private final UndoJournal<BlockState, CompoundTag> journal;
	/** The parts the moving build had as this build began (a refinement's); none for a new build. */
	private final Map<String, AnimationStore.Part> earlier;

	/** A new build's parts: a moving build of their own. Server thread. */
	public MovingParts(ServerLevel level, LocalFrame frame, AnimationStore store, PlacementJob job, UndoJournal<BlockState, CompoundTag> journal) {
		this(level, new AnimationStore.Key(UUID.randomUUID(), level.dimension(), frame, level.getGameTime()), store, job, journal, Map.of());
	}

	private MovingParts(ServerLevel level, AnimationStore.Key key, AnimationStore store, PlacementJob job, UndoJournal<BlockState, CompoundTag> journal,
			Map<String, AnimationStore.Part> earlier) {
		this.level = level;
		this.store = store;
		this.key = key;
		this.journal = journal;
		this.earlier = earlier;
		LocalFrame frame = key.frame();
		job.onDisplay((part, display, at) -> store.display(key, part, new AnimationStore.PartDisplay(display, frame.toLocal(Positions.toPos(at)))));
		Map<String, Map<BlockPos, UUID>> known = new HashMap<>();
		earlier.forEach((name, part) -> part.displays().forEach(d -> known.computeIfAbsent(name, n -> new HashMap<>())
				.put(Positions.toBlockPos(frame.toWorld(d.block())), d.id())));
		job.knownDisplays(known);
	}

	/**
	 * A refinement's parts (spec v2 §4.4): they join the moving build {@code id}, its parts and its clock, while the
	 * store still has it; else they're a new build's. Server thread.
	 */
	public static MovingParts refining(ServerLevel level, LocalFrame frame, AnimationStore store, PlacementJob job,
			UndoJournal<BlockState, CompoundTag> journal, UUID id) {
		return store.build(id).map(build -> {
			Map<String, AnimationStore.Part> parts = new LinkedHashMap<>();
			build.parts().forEach(part -> parts.put(part.motion().name(), part));
			return new MovingParts(level, build.key(), store, job, journal, parts);
		}).orElseGet(() -> new MovingParts(level, frame, store, job, journal));
	}

	public AnimationStore.Key key() {
		return key;
	}

	/**
	 * The earlier parts as the tools are told of them: their blocks, those a display hides (air written into the part)
	 * aside, and their motions (none while one stands still). Server thread.
	 */
	public Map<String, PartInfo> earlierParts() {
		Map<String, PartInfo> out = new LinkedHashMap<>();
		earlier.forEach((name, part) -> {
			MovingPart motion = part.motion();
			boolean still = motion.motions().isEmpty() && motion.parent().isEmpty();
			out.put(name, new PartInfo(part.displays().stream().filter(d -> !hidden(d)).map(AnimationStore.PartDisplay::block).toList(),
					still ? null : motion));
		});
		return out;
	}

	private boolean hidden(AnimationStore.PartDisplay display) {
		return level.getEntity(display.id()) instanceof Display.BlockDisplay entity && ((BlockDisplayAccessor) entity).aibuilder$blockState().isAir();
	}

	/** Whether the store has the moving build (it has a part): the session remembers it then. Server thread. */
	public boolean inStore() {
		return store.has(key.id());
	}

	/**
	 * A part's motion as animate gave it. Any thread: it reaches the store on the server thread, an earlier part's
	 * motion going into the journal first (the first time), so undoing this build gives it back.
	 */
	public void animate(MovingPart part) {
		level.getServer().execute(() -> {
			if (earlier.containsKey(part.name())) {
				store.motion(key.id(), part.name()).ifPresent(before -> journal.reanimated(key.id(), before));
			}
			store.animate(key, part);
		});
	}
}
