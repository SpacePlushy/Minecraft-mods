package io.palmisano.aibuilder.world;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import io.palmisano.aibuilder.core.anim.MovingPart;
import io.palmisano.aibuilder.core.anim.Poses;
import io.palmisano.aibuilder.core.anim.Rigid;
import io.palmisano.aibuilder.core.frame.Facing;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.ChunkPos;

/**
 * Moves the world's moving parts (spec v2 §4.3). Each tick, a part whose step has come (every 4 ticks, or more often
 * for a fast one: {@link Poses#stepTicks}) has its displays queued; then as many as the tick's time allows, and at
 * least one, are told the part's pose one step ahead and glide to it over the step. A part isn't queued again until
 * its last display has had its turn, so a big build spreads over ticks instead of piling up. A display whose chunk
 * has no entities loaded waits for a step that finds them loaded; one that's gone while they are (killed, or taken
 * down) is forgotten. While moving_parts is false nothing is told anything, so every part stays where it is. Server
 * thread.
 */
public final class AnimationEngine {
	private record PartKey(UUID build, String part) {
	}

	private record Work(AnimationStore.Live build, String part, AnimationStore.PartDisplay display, boolean last) {
	}

	private final MinecraftServer server;
	private final AnimationStore store;
	private final BooleanSupplier running;
	private final long nanosPerTick;
	private final ArrayDeque<Work> queue = new ArrayDeque<>();
	/** Parts with displays in the queue. */
	private final Set<PartKey> queued = new HashSet<>();

	public AnimationEngine(MinecraftServer server, AnimationStore store, BooleanSupplier running, long nanosPerTick) {
		this.server = server;
		this.store = store;
		this.running = running;
		this.nanosPerTick = nanosPerTick;
	}

	/** One tick, the world's game time being {@code now}. */
	public void tick(long now) {
		if (store.live().isEmpty() && queue.isEmpty()) {
			return;
		}
		if (!running.getAsBoolean()) {
			queue.clear();
			queued.clear();
			return;
		}
		for (AnimationStore.Live build : store.live()) {
			ServerLevel level = server.getLevel(build.key.dimension());
			if (level != null) {
				due(build, level, now);
			}
		}
		long deadline = System.nanoTime() + nanosPerTick;
		Map<PartKey, Rigid> poses = new HashMap<>();
		Set<UUID> gone = new HashSet<>();
		do {
			Work work = queue.poll();
			if (work == null) {
				break;
			}
			if (work.last()) {
				queued.remove(new PartKey(work.build().key.id(), work.part()));
			}
			pose(work, now, poses, gone);
		} while (System.nanoTime() < deadline);
		if (!gone.isEmpty()) {
			store.forget(gone);
		}
	}

	/** Queues the displays, in loaded chunks, of each of the build's parts whose step has come. */
	private void due(AnimationStore.Live build, ServerLevel level, long now) {
		Map<String, MovingPart> motions = build.motions();
		build.parts.forEach((name, part) -> {
			if (part.standsStill() || Math.floorMod(now - build.key.start(), Poses.stepTicks(name, motions)) != 0) {
				return;
			}
			List<AnimationStore.PartDisplay> displays = part.displays.stream().filter(d -> loaded(level, build, d)).toList();
			if (displays.isEmpty() || !queued.add(new PartKey(build.key.id(), name))) {
				return;
			}
			for (int k = 0; k < displays.size(); k++) {
				queue.add(new Work(build, name, displays.get(k), k == displays.size() - 1));
			}
		});
	}

	private void pose(Work work, long now, Map<PartKey, Rigid> poses, Set<UUID> gone) {
		AnimationStore.Live build = work.build();
		ServerLevel level = server.getLevel(build.key.dimension());
		if (!store.holds(build) || level == null || !loaded(level, build, work.display())) {
			return;
		}
		if (!(level.getEntity(work.display().id()) instanceof Display.BlockDisplay display) || display.isRemoved()) {
			gone.add(work.display().id());
			return;
		}
		Map<String, MovingPart> motions = build.motions();
		Facing facing = build.key.frame().facing();
		int step = Poses.stepTicks(work.part(), motions);
		Rigid pose = poses.computeIfAbsent(new PartKey(build.key.id(), work.part()),
				key -> Poses.rigid(key.part(), motions, now + step - build.key.start(), facing));
		Displays.pose(display, Poses.display(pose, work.display().block(), facing), step);
	}

	/** Whether the entities of the display's chunk are loaded: if it's there, it's found. */
	private static boolean loaded(ServerLevel level, AnimationStore.Live build, AnimationStore.PartDisplay display) {
		BlockPos corner = Positions.toBlockPos(build.key.frame().toWorld(display.block()));
		return level.areEntitiesLoaded(ChunkPos.containing(corner).pack());
	}
}
