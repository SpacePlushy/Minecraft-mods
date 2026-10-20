package io.palmisano.drone.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.palmisano.drone.DroneMod;
import io.palmisano.drone.entity.DroneEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

public class DroneRenderer extends EntityRenderer<DroneEntity, DroneRenderState> {
	private static final Identifier TEXTURE = DroneMod.id("textures/entity/drone.png");

	private final DroneModel model;

	public DroneRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.model = new DroneModel(context.bakeLayer(DroneModel.LAYER));
		this.shadowRadius = 0.6f;
	}

	@Override
	public DroneRenderState createRenderState() {
		return new DroneRenderState();
	}

	@Override
	public void extractRenderState(DroneEntity drone, DroneRenderState state, float partialTick) {
		super.extractRenderState(drone, state, partialTick);
		state.yRot = drone.getYRot(partialTick);
		state.pitchTilt = drone.getPitchTilt(partialTick);
		state.rollTilt = drone.getRollTilt(partialTick);
		state.propAngle = drone.getPropAngle(partialTick);
	}

	@Override
	public void submit(DroneRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		pose.pushPose();
		pose.translate(0f, 0.25f, 0f); // body center sits 4 px above the hitbox bottom
		pose.rotateDegrees(Axis.YP, 180 - state.yRot);
		pose.scale(-1, -1, 1);
		collector.submitModel(model, state, pose, TEXTURE, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
		pose.popPose();
		super.submit(state, pose, collector, camera);
	}
}
