package io.palmisano.aibuilder.core.place;

import java.util.BitSet;

import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.Box;

/**
 * Which local positions in the allowed box hold blocks a build must leave alone (spec §11): ones that look
 * player-made, and unbreakable ones (bedrock, barriers…), which nothing may replace. Read from the world when a
 * build starts; after that only the build's own tool thread uses it.
 */
public final class ProtectionMask {
	private final Box box;
	private final BitSet protectedBits = new BitSet();
	private final BitSet unbreakableBits = new BitSet();

	/** Nothing protected anywhere. */
	public static ProtectionMask none() {
		return new ProtectionMask(Box.of(0, 0, 0, 0, 0, 0));
	}

	public ProtectionMask(Box box) {
		this.box = box;
	}

	public void protect(Pos local) {
		int i = index(local);
		if (i >= 0) {
			protectedBits.set(i);
		}
	}

	public void unbreakable(Pos local) {
		int i = index(local);
		if (i >= 0) {
			protectedBits.set(i);
			unbreakableBits.set(i);
		}
	}

	public boolean isProtected(Pos local) {
		int i = index(local);
		return i >= 0 && protectedBits.get(i);
	}

	public boolean isUnbreakable(Pos local) {
		int i = index(local);
		return i >= 0 && unbreakableBits.get(i);
	}

	/** The build has cleared this spot itself: from now on it's the build's own. Unbreakable spots stay. */
	public void release(Pos local) {
		int i = index(local);
		if (i >= 0 && !unbreakableBits.get(i)) {
			protectedBits.clear(i);
		}
	}

	public int count() {
		return protectedBits.cardinality();
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
