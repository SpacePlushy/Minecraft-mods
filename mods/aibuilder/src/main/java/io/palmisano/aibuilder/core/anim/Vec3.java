package io.palmisano.aibuilder.core.anim;

/** A point or a direction, in blocks. */
public record Vec3(double x, double y, double z) {
	public static final Vec3 ZERO = new Vec3(0, 0, 0);

	public Vec3 plus(Vec3 other) {
		return new Vec3(x + other.x, y + other.y, z + other.z);
	}

	public Vec3 minus(Vec3 other) {
		return new Vec3(x - other.x, y - other.y, z - other.z);
	}

	public Vec3 times(double k) {
		return new Vec3(x * k, y * k, z * k);
	}
}
