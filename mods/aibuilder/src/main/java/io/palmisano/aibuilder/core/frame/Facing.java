package io.palmisano.aibuilder.core.frame;

/** The way the player faces, snapped to a compass direction. Minecraft's +X is east, +Z south. */
public enum Facing {
	NORTH(0, -1, 2),
	EAST(1, 0, 3),
	SOUTH(0, 1, 0),
	WEST(-1, 0, 1);

	private final int forwardX;
	private final int forwardZ;
	private final int quarterTurnsFromSouth;

	Facing(int forwardX, int forwardZ, int quarterTurnsFromSouth) {
		this.forwardX = forwardX;
		this.forwardZ = forwardZ;
		this.quarterTurnsFromSouth = quarterTurnsFromSouth;
	}

	/** Minecraft yaw: 0 faces south, 90 west, 180 north, -90 east; any multiple of 360 wraps. */
	public static Facing fromYaw(float yaw) {
		return switch (Math.floorMod(Math.round(yaw / 90f), 4)) {
			case 0 -> SOUTH;
			case 1 -> WEST;
			case 2 -> NORTH;
			default -> EAST;
		};
	}

	public int forwardX() {
		return forwardX;
	}

	public int forwardZ() {
		return forwardZ;
	}

	/** The player's right: forward turned clockwise, seen from above. */
	public int rightX() {
		return -forwardZ;
	}

	public int rightZ() {
		return forwardX;
	}

	/** Clockwise quarter turns (seen from above) that take south to this facing. */
	public int quarterTurnsFromSouth() {
		return quarterTurnsFromSouth;
	}
}
