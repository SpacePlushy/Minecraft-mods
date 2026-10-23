package io.palmisano.aibuilder.core.anim;

/**
 * A rotation, as a unit quaternion. Right-handed, as Minecraft's world axes are: a positive angle turns clockwise
 * as you look along its axis.
 */
public record Quat(double w, double x, double y, double z) {
	public static final Quat IDENTITY = new Quat(1, 0, 0, 0);

	/** {@code degrees} about {@code unitAxis}, clockwise looking along it. */
	public static Quat axisAngle(Vec3 unitAxis, double degrees) {
		double half = Math.toRadians(degrees) / 2;
		double s = Math.sin(half);
		return new Quat(Math.cos(half), unitAxis.x() * s, unitAxis.y() * s, unitAxis.z() * s);
	}

	/** This rotation after {@code first}: {@code this.times(first).rotate(v)} is {@code this.rotate(first.rotate(v))}. */
	public Quat times(Quat first) {
		return new Quat(
				w * first.w - x * first.x - y * first.y - z * first.z,
				w * first.x + x * first.w + y * first.z - z * first.y,
				w * first.y - x * first.z + y * first.w + z * first.x,
				w * first.z + x * first.y - y * first.x + z * first.w);
	}

	/** {@code v} turned: v + 2w(u × v) + 2u × (u × v), with u the quaternion's vector part. */
	public Vec3 rotate(Vec3 v) {
		double cx = y * v.z() - z * v.y();
		double cy = z * v.x() - x * v.z();
		double cz = x * v.y() - y * v.x();
		return new Vec3(
				v.x() + 2 * (w * cx + y * cz - z * cy),
				v.y() + 2 * (w * cy + z * cx - x * cz),
				v.z() + 2 * (w * cz + x * cy - y * cx));
	}
}
