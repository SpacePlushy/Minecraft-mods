package io.palmisano.rocket.client;

import net.minecraft.client.renderer.entity.state.EntityRenderState;

/** Per-frame snapshot of a rocket, interpolated for the frame's partial tick. Angles in degrees. */
public class RocketRenderState extends EntityRenderState {
	public float yRot;
	public float pitchTilt;
	public float rollTilt;
}
