package io.palmisano.rocket.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.palmisano.rocket.RocketMod;
import io.palmisano.rocket.entity.RocketEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;

public class RocketRenderer extends EntityRenderer<RocketEntity, RocketRenderState> {
	private static final Identifier TEXTURE = RocketMod.id("textures/entity/rocket.png");
	private static final float PIVOT_HEIGHT = 2.75f; // cabin center: the seated rider stays inside when it tilts

	private final RocketModel model;

	public RocketRenderer(EntityRendererProvider.Context context) {
		super(context);
		this.model = new RocketModel(context.bakeLayer(RocketModel.LAYER));
		this.shadowRadius = 0.8f;
	}

	@Override
	public RocketRenderState createRenderState() {
		return new RocketRenderState();
	}

	@Override
	public void extractRenderState(RocketEntity rocket, RocketRenderState state, float partialTick) {
		super.extractRenderState(rocket, state, partialTick);
		state.yRot = rocket.getYRot(partialTick);
		state.pitchTilt = rocket.getPitchTilt(partialTick);
		state.rollTilt = rocket.getRollTilt(partialTick);
	}

	@Override
	public void submit(RocketRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		pose.pushPose();
		pose.translate(0f, PIVOT_HEIGHT, 0f);
		pose.rotateDegrees(Axis.YP, 180 - state.yRot);
		pose.scale(-1, -1, 1);
		collector.submitModel(model, state, pose, TEXTURE, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
		pose.popPose();
		super.submit(state, pose, collector, camera);
	}
}
