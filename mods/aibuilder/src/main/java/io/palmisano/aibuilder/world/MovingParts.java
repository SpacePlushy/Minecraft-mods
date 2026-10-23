package io.palmisano.aibuilder.world;

import java.util.UUID;

import io.palmisano.aibuilder.core.anim.MovingPart;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import net.minecraft.server.level.ServerLevel;

/**
 * One build's moving parts into an {@link AnimationStore} as they're made (spec v2 §4.4): each display its placement
 * job makes and each motion animate gives, so a part moves as soon as it has both, while the build goes on. Its motion
 * counts from the game time the build began. The store hears of the build only once it has a part.
 */
public final class MovingParts {
	private final ServerLevel level;
	private final AnimationStore store;
	private final AnimationStore.Key key;

	/** Server thread: from here on the job's displays go into the store. */
	public MovingParts(ServerLevel level, LocalFrame frame, AnimationStore store, PlacementJob job) {
		this.level = level;
		this.store = store;
		this.key = new AnimationStore.Key(UUID.randomUUID(), level.dimension(), frame, level.getGameTime());
		job.onDisplay((part, display, at) -> store.display(key, part, new AnimationStore.PartDisplay(display, frame.toLocal(Positions.toPos(at)))));
	}

	public AnimationStore.Key key() {
		return key;
	}

	/** A part's motion as animate gave it. Any thread: it reaches the store on the server thread. */
	public void animate(MovingPart part) {
		level.getServer().execute(() -> store.animate(key, part));
	}
}
