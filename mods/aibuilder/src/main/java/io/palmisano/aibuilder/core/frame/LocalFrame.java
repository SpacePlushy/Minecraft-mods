package io.palmisano.aibuilder.core.frame;

/**
 * The build's frame: +x is the player's right, +z forward (away from them), +y up, the origin on the ground in
 * front of them. World = origin + x·right + y·up + z·forward.
 */
public record LocalFrame(Pos origin, Facing facing) {
	public Pos toWorld(Pos local) {
		return new Pos(
				origin.x() + local.x() * facing.rightX() + local.z() * facing.forwardX(),
				origin.y() + local.y(),
				origin.z() + local.x() * facing.rightZ() + local.z() * facing.forwardZ());
	}

	public Pos toLocal(Pos world) {
		int dx = world.x() - origin.x();
		int dz = world.z() - origin.z();
		return new Pos(
				dx * facing.rightX() + dz * facing.rightZ(),
				world.y() - origin.y(),
				dx * facing.forwardX() + dz * facing.forwardZ());
	}
}
