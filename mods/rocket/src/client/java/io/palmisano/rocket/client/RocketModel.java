package io.palmisano.rocket.client;

import io.palmisano.rocket.RocketMod;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;

/**
 * Blocky rocket, about 4.6 blocks tall. Model space is in pixels with Y down, origin at the cabin
 * center (the tilt pivot, 44 px above the base), front = -Z and right = -X. UVs must match
 * tools/gen_textures.py. Back faces are culled, so a rider inside the cabin sees out of it.
 */
public class RocketModel extends EntityModel<RocketRenderState> {
	public static final ModelLayerLocation LAYER = new ModelLayerLocation(RocketMod.id("rocket"), "main");

	private final ModelPart rocket;

	public RocketModel(ModelPart root) {
		super(root, RenderTypes::entityCutoutCull);
		this.rocket = root.getChild("rocket");
	}

	public static LayerDefinition createLayer() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition rocket = mesh.getRoot().addOrReplaceChild("rocket", CubeListBuilder.create(), PartPose.ZERO);

		rocket.addOrReplaceChild("nozzle", CubeListBuilder.create().texOffs(64, 110).addBox(-6, 38, -6, 12, 6, 12), PartPose.ZERO);
		rocket.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 46).addBox(-11, 12, -11, 22, 26, 22), PartPose.ZERO);
		rocket.addOrReplaceChild("cabin", CubeListBuilder.create().texOffs(0, 0).addBox(-11, -12, -11, 22, 24, 22), PartPose.ZERO);
		rocket.addOrReplaceChild("nose1", CubeListBuilder.create().texOffs(0, 94).addBox(-8, -20, -8, 16, 8, 16), PartPose.ZERO);
		rocket.addOrReplaceChild("nose2", CubeListBuilder.create().texOffs(64, 94).addBox(-5, -26, -5, 10, 6, 10), PartPose.ZERO);
		rocket.addOrReplaceChild("nose3", CubeListBuilder.create().texOffs(104, 94).addBox(-2, -30, -2, 4, 4, 4), PartPose.ZERO);

		CubeListBuilder fin = CubeListBuilder.create().texOffs(88, 0).addBox(11, 24, -1, 8, 14, 2);
		for (int i = 0; i < 4; i++) {
			rocket.addOrReplaceChild("fin_" + i, fin, PartPose.rotation(0, i * Mth.HALF_PI, 0));
		}

		return LayerDefinition.create(mesh, 128, 128);
	}

	@Override
	public void setupAnim(RocketRenderState state) {
		super.setupAnim(state);
		rocket.xRot = state.pitchTilt * Mth.DEG_TO_RAD; // nose (up, -Y) tips toward the front (-Z)
		rocket.zRot = -state.rollTilt * Mth.DEG_TO_RAD; // nose tips toward the right (-X)
	}
}
