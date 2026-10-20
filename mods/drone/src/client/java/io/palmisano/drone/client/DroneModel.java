package io.palmisano.drone.client;

import io.palmisano.drone.DroneMod;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * Blocky quadcopter. Model space is in pixels with Y down, origin at the body center,
 * front = -Z and right = -X. UVs must match tools/gen_textures.py.
 */
public class DroneModel extends EntityModel<DroneRenderState> {
	public static final ModelLayerLocation LAYER = new ModelLayerLocation(DroneMod.id("drone"), "main");

	private static final float ARM_ANGLE = (float) Math.PI / 4;

	private final ModelPart frame;
	private final ModelPart propFrontLeft;
	private final ModelPart propFrontRight;
	private final ModelPart propBackLeft;
	private final ModelPart propBackRight;

	public DroneModel(ModelPart root) {
		super(root);
		this.frame = root.getChild("frame");
		this.propFrontLeft = frame.getChild("prop_front_left");
		this.propFrontRight = frame.getChild("prop_front_right");
		this.propBackLeft = frame.getChild("prop_back_left");
		this.propBackRight = frame.getChild("prop_back_right");
	}

	public static LayerDefinition createLayer() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition frame = mesh.getRoot().addOrReplaceChild("frame", CubeListBuilder.create(), PartPose.ZERO);

		frame.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 0).addBox(-4, -2, -4, 8, 4, 8), PartPose.ZERO);

		CubeListBuilder arm = CubeListBuilder.create().texOffs(0, 16).addBox(-10, -1, -1, 20, 2, 2);
		frame.addOrReplaceChild("arm_a", arm, PartPose.offsetAndRotation(0, 0, 0, 0, ARM_ANGLE, 0));
		frame.addOrReplaceChild("arm_b", arm, PartPose.offsetAndRotation(0, 0, 0, 0, -ARM_ANGLE, 0));

		CubeListBuilder frontMotor = CubeListBuilder.create().texOffs(0, 20).addBox(-1.5f, -3, -1.5f, 3, 3, 3);
		CubeListBuilder backMotor = CubeListBuilder.create().texOffs(12, 20).addBox(-1.5f, -3, -1.5f, 3, 3, 3);
		frame.addOrReplaceChild("motor_front_left", frontMotor, PartPose.offset(7, -1, -7));
		frame.addOrReplaceChild("motor_front_right", frontMotor, PartPose.offset(-7, -1, -7));
		frame.addOrReplaceChild("motor_back_left", backMotor, PartPose.offset(7, -1, 7));
		frame.addOrReplaceChild("motor_back_right", backMotor, PartPose.offset(-7, -1, 7));

		CubeListBuilder skid = CubeListBuilder.create().texOffs(24, 20).addBox(-0.5f, 0, -6, 1, 2, 12);
		frame.addOrReplaceChild("skid_left", skid, PartPose.offset(4, 2, 0));
		frame.addOrReplaceChild("skid_right", skid, PartPose.offset(-4, 2, 0));

		CubeListBuilder prop = CubeListBuilder.create().texOffs(0, 34).addBox(-5, -1, -1, 10, 1, 2);
		frame.addOrReplaceChild("prop_front_left", prop, PartPose.offset(7, -4, -7));
		frame.addOrReplaceChild("prop_front_right", prop, PartPose.offset(-7, -4, -7));
		frame.addOrReplaceChild("prop_back_left", prop, PartPose.offset(7, -4, 7));
		frame.addOrReplaceChild("prop_back_right", prop, PartPose.offset(-7, -4, 7));

		return LayerDefinition.create(mesh, 64, 64);
	}

	@Override
	public void setupAnim(DroneRenderState state) {
		super.setupAnim(state);
		frame.xRot = state.pitchTilt * Mth.DEG_TO_RAD; // nose (-Z) down
		frame.zRot = -state.rollTilt * Mth.DEG_TO_RAD; // right side (-X) down
		float spin = state.propAngle * Mth.DEG_TO_RAD; // diagonal pairs counter-rotate, like a real quad
		propFrontLeft.yRot = spin;
		propBackRight.yRot = spin;
		propFrontRight.yRot = -spin;
		propBackLeft.yRot = -spin;
	}
}
