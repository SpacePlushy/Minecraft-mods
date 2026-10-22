package io.palmisano.aibuilder.core.place;

import io.palmisano.aibuilder.core.blocks.BlockInfo;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.Box;

/**
 * Spec §9: lava never goes in where it could set something alight, nor anything that burns where lava could reach
 * it, counting the world (read when the build started) and the build so far, a call's earlier blocks included. Not
 * thread-safe: the tool executor's lock covers it.
 */
public final class FireRule {
	public enum Kind {
		LAVA,
		FLAMMABLE,
		OTHER;

		public static Kind of(BlockInfo info) {
			return info.id().equals(BlockInfo.LAVA) ? LAVA : info.flammable() ? FLAMMABLE : OTHER;
		}
	}

	private final FireMask world;
	private final FireMask own;

	/** {@code world} is the build's own copy: the build's blocks replace what it says. */
	public FireRule(FireMask world, Box allowed) {
		this.world = world;
		this.own = new FireMask(allowed);
	}

	/**
	 * Whether a block of this kind can go at {@code pos}; if it can, it's remembered. {@code conditional}: it replaces
	 * only some blocks (fill's replace mode), so what's there now may stay, and both count from then on.
	 */
	public boolean admit(Pos pos, Kind kind, boolean conditional) {
		if (!allows(pos, kind)) {
			return false;
		}
		if (!conditional) {
			world.clear(pos);
			own.clear(pos);
		}
		if (kind == Kind.LAVA) {
			own.lava(pos);
		} else if (kind == Kind.FLAMMABLE) {
			own.flammable(pos);
		}
		return true;
	}

	/** Whether a block of this kind could go at {@code pos}, remembering nothing: for a block of two, both halves first. */
	public boolean allows(Pos pos, Kind kind) {
		return !(kind == Kind.LAVA && flammableNear(pos) || kind == Kind.FLAMMABLE && lavaNear(pos));
	}

	/** Something that burns where lava at {@code lava} would reach it; its own spot doesn't count, it gets replaced. */
	private boolean flammableNear(Pos lava) {
		for (int y = lava.y() - FireMask.BELOW; y <= lava.y() + FireMask.ABOVE; y++) {
			for (int z = lava.z() - FireMask.SIDEWAYS; z <= lava.z() + FireMask.SIDEWAYS; z++) {
				for (int x = lava.x() - FireMask.SIDEWAYS; x <= lava.x() + FireMask.SIDEWAYS; x++) {
					Pos p = new Pos(x, y, z);
					if (!p.equals(lava) && (own.isFlammable(p) || world.isFlammable(p))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Lava that would reach something that burns at {@code target}: from 4 below it to 1 above, 3 sideways. */
	private boolean lavaNear(Pos target) {
		if (!own.lavaMayReach(target) && !world.lavaMayReach(target)) {
			return false;
		}
		for (int y = target.y() - FireMask.ABOVE; y <= target.y() + FireMask.BELOW; y++) {
			for (int z = target.z() - FireMask.SIDEWAYS; z <= target.z() + FireMask.SIDEWAYS; z++) {
				for (int x = target.x() - FireMask.SIDEWAYS; x <= target.x() + FireMask.SIDEWAYS; x++) {
					Pos p = new Pos(x, y, z);
					if (!p.equals(target) && (own.isLava(p) || world.isLava(p))) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
