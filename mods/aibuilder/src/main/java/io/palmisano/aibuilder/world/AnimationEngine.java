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
 * for a fast one: {@link Poses#stepTicks}; parts take their turns at different ticks of the step, so the load spreads)
 * has its displays queued for one round; then as many as the tick's time allows, and at least one, are told the part's
 * pose a round on from that tick and glide to it over the round. The client glides a display from wherever it is when
 * told, so each display keeps to the part's path whichever tick its turn falls on, none standing while another moves
 * on: a part spread over ticks holds together. A part isn't queued again until its round is done, and a round that took
 * longer than a step makes the next aim and glide as far, so a big build lags as a whole rather than stopping and
 * starting. A part that stands still isn't moved, but each of its displays is put back at rest once: an undo that gives
 * it its stillness back, or a world saved mid-move, may have left it where a motion had got it to. A display whose
 * chunk has no entities loaded waits for a round that finds them loaded; one that's gone while they are (killed, or
 * taken down) is forgotten. While moving_parts is false nothing is told anything, so every part stays where it is.
 * Server thread.
 */
public final class AnimationEngine {
	private record PartKey(UUID build, String part) {
	}

	/** One display's turn in its part's round: told the part's pose {@code lead} ticks on, it glides there over them. */
	private record Work(AnimationStore.Live build, String part, AnimationStore.PartDisplay display, boolean last, long lead) {
	}

	private final MinecraftServer server;
	private final AnimationStore store;
	private final BooleanSupplier running;
	private final long nanosPerTick;
	private final ArrayDeque<Work> queue = new ArrayDeque<>();
	/** Parts with displays in the queue: a round under way. */
	private final Set<PartKey> queued = new HashSet<>();
	/** When each part's round under way began, and how many ticks its last round took. */
	private final Map<PartKey, Long> roundBegan = new HashMap<>();
	private final Map<PartKey, Long> roundTicks = new HashMap<>();

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
				PartKey key = new PartKey(work.build().key.id(), work.part());
				queued.remove(key);
				roundTicks.put(key, now - roundBegan.getOrDefault(key, now) + 1);
			}
			pose(work, now, poses, gone);
		} while (System.nanoTime() < deadline);
		if (!gone.isEmpty()) {
			store.forget(gone);
		}
	}

	/**
	 * Queues a round for each of the build's parts whose turn has come and has none under way: its displays in loaded
	 * chunks (of a part that stands still, those not yet put back at rest), each to aim a step on from its own turn, or
	 * as far as the part's last round took.
	 */
	private void due(AnimationStore.Live build, ServerLevel level, long now) {
		Map<String, MovingPart> motions = build.motions();
		build.parts.forEach((name, part) -> {
			int step = Poses.stepTicks(name, motions);
			PartKey key = new PartKey(build.key.id(), name);
			if (Math.floorMod(now - build.key.start() - turn(key, step), step) != 0 || queued.contains(key)) {
				return;
			}
			if (part.standsStill() && part.unrested == null) {
				part.unrested = new HashSet<>();
				part.displays.forEach(d -> part.unrested.add(d.id()));
			}
			List<AnimationStore.PartDisplay> displays = part.displays.stream()
					.filter(d -> (!part.standsStill() || part.unrested.contains(d.id())) && loaded(level, build, d)).toList();
			if (displays.isEmpty()) {
				return;
			}
			queued.add(key);
			roundBegan.put(key, now);
			long took = roundTicks.getOrDefault(key, 0L);
			long lead = Math.max(step, (took + step - 1) / step * step);
			for (int k = 0; k < displays.size(); k++) {
				queue.add(new Work(build, name, displays.get(k), k == displays.size() - 1, lead));
			}
		});
	}

	/** Which tick of its step a part takes its turn on: spread by the part, so parts don't all come due at once. */
	private static int turn(PartKey key, int step) {
		return Math.floorMod(key.hashCode(), step);
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
		Rigid pose = poses.computeIfAbsent(new PartKey(build.key.id(), work.part()),
				key -> Poses.rigid(key.part(), motions, now + work.lead() - build.key.start(), facing));
		// A round on from its own turn, over the round: a display told a tick after another keeps to the same path.
		Displays.pose(display, Poses.display(pose, work.display().block(), facing), (int) work.lead());
		AnimationStore.LivePart part = build.parts.get(work.part());
		if (part != null && part.unrested != null) {
			part.unrested.remove(work.display().id());
		}
	}

	/** Whether the entities of the display's chunk are loaded: if it's there, it's found. */
	private static boolean loaded(ServerLevel level, AnimationStore.Live build, AnimationStore.PartDisplay display) {
		BlockPos corner = Positions.toBlockPos(build.key.frame().toWorld(display.block()));
		return level.areEntitiesLoaded(ChunkPos.containing(corner).pack());
	}
}
