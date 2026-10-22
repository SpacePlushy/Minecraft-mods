package io.palmisano.aibuilder.core.place;

import java.util.BitSet;

import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.Box;

/**
 * Lava and flammable blocks over a box, for spec §9's rule: lava never goes in where it could set something alight,
 * even though the mod never lets it flow (lava lights fires by itself). The world's are read when a build starts,
 * over the allowed box and as far around it as lava reaches; after that only the build's own tool calls change it.
 */
public final class FireMask {
	/** How far lava sets things alight: within 3 blocks sideways, from 1 below it to 4 above it (vanilla's reach). */
	public static final int SIDEWAYS = 3;
	public static final int BELOW = 1;
	public static final int ABOVE = 4;

	private final Box box;
	private final BitSet lavaBits = new BitSet();
	private final BitSet flammableBits = new BitSet();
	// The lava's bounds, never shrunk: a quick "no lava anywhere near" before looking block by block.
	private int lavaMinX = Integer.MAX_VALUE;
	private int lavaMinY = Integer.MAX_VALUE;
	private int lavaMinZ = Integer.MAX_VALUE;
	private int lavaMaxX = Integer.MIN_VALUE;
	private int lavaMaxY = Integer.MIN_VALUE;
	private int lavaMaxZ = Integer.MIN_VALUE;

	/** Nothing known anywhere. */
	public static FireMask none() {
		return new FireMask(Box.of(0, 0, 0, 0, 0, 0));
	}

	public FireMask(Box box) {
		this.box = box;
	}

	/** A mask over the allowed box and the ring around it where lava could reach in, or be reached from. */
	public static FireMask around(Box allowed) {
		return new FireMask(Box.of(allowed.min().x() - SIDEWAYS, allowed.min().y() - ABOVE, allowed.min().z() - SIDEWAYS,
				allowed.max().x() + SIDEWAYS, allowed.max().y() + ABOVE, allowed.max().z() + SIDEWAYS));
	}

	public Box box() {
		return box;
	}

	/** Whether lava at {@code lava} could set {@code other} alight. */
	public static boolean inDanger(Pos lava, Pos other) {
		int dy = other.y() - lava.y();
		return Math.abs(other.x() - lava.x()) <= SIDEWAYS && Math.abs(other.z() - lava.z()) <= SIDEWAYS && dy >= -BELOW && dy <= ABOVE;
	}

	public void lava(Pos local) {
		int i = index(local);
		if (i >= 0) {
			lavaBits.set(i);
			lavaMinX = Math.min(lavaMinX, local.x());
			lavaMinY = Math.min(lavaMinY, local.y());
			lavaMinZ = Math.min(lavaMinZ, local.z());
			lavaMaxX = Math.max(lavaMaxX, local.x());
			lavaMaxY = Math.max(lavaMaxY, local.y());
			lavaMaxZ = Math.max(lavaMaxZ, local.z());
		}
	}

	public void flammable(Pos local) {
		int i = index(local);
		if (i >= 0) {
			flammableBits.set(i);
		}
	}

	/** Neither any more: the build put something else there. */
	public void clear(Pos local) {
		int i = index(local);
		if (i >= 0) {
			lavaBits.clear(i);
			flammableBits.clear(i);
		}
	}

	public boolean isLava(Pos local) {
		int i = index(local);
		return i >= 0 && lavaBits.get(i);
	}

	public boolean isFlammable(Pos local) {
		int i = index(local);
		return i >= 0 && flammableBits.get(i);
	}

	public boolean anyLava() {
		return !lavaBits.isEmpty();
	}

	/** False when no lava here could reach {@code other}; true means look closer. */
	public boolean lavaMayReach(Pos other) {
		return anyLava() && other.x() >= lavaMinX - SIDEWAYS && other.x() <= lavaMaxX + SIDEWAYS && other.z() >= lavaMinZ - SIDEWAYS
				&& other.z() <= lavaMaxZ + SIDEWAYS && other.y() >= lavaMinY - BELOW && other.y() <= lavaMaxY + ABOVE;
	}

	private int index(Pos p) {
		if (!box.contains(p)) {
			return -1;
		}
		int width = box.max().x() - box.min().x() + 1;
		int depth = box.max().z() - box.min().z() + 1;
		return ((p.y() - box.min().y()) * depth + (p.z() - box.min().z())) * width + (p.x() - box.min().x());
	}
}
