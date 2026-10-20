package io.palmisano.drone.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Per-frame snapshot of a drone, interpolated for the frame's partial tick. Angles in degrees. */
public class DroneRenderState extends EntityRenderState {
	public float yRot;
	public float pitchTilt;
	public float rollTilt;
	public float propAngle;
}
