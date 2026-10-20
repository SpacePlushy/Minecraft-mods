package io.palmisano.drone.entity;

import io.palmisano.drone.flight.DroneInput;
import io.palmisano.drone.flight.FlightModel;
import io.palmisano.drone.flight.FlightState;
import io.palmisano.drone.flight.FlightTuning;
import io.palmisano.drone.registry.ModItems;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LinearInterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Rideable quadcopter. Like vanilla boats, whichever side controls it (the rider's client, or the
 * server when unridden) steps {@link FlightModel} and moves; the other side just interpolates.
 */
public class DroneEntity extends VehicleEntity {
	// Render-only propeller spin, degrees per tick.
	private static final float PROP_BASE = 45;
	private static final float PROP_PER_TILT = 1.5f;
	private static final float PROP_PER_CLIMB = 120;
	private static final float PROP_WRAP = 36000;

	private final FlightModel flight = new FlightModel(FlightTuning.DEFAULT);
	private FlightState state = FlightState.hovering(0);
	private DroneInput input;
	private Vec3 lastTickPos; // vehicle-move packets land between ticks, so xo/yo/zo can't see them
	private double pitchTiltO;
	private double rollTiltO;
	private float propAngle;
	private float propAngleO;

	public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
		super(type, level);
		this.blocksBuilding = true;
	}

	/** Latest pilot input from the rider's client; read on the next authoritative tick. */
	public void setInput(DroneInput input) {
		this.input = input;
	}

	public float getPitchTilt(float partialTick) {
		return (float) Mth.lerp(partialTick, pitchTiltO, state.pitchTilt());
	}

	public float getRollTilt(float partialTick) {
		return (float) Mth.lerp(partialTick, rollTiltO, state.rollTilt());
	}

	public float getPropAngle(float partialTick) {
		return Mth.lerp(partialTick, propAngleO, propAngle);
	}

	@Override
	public void tick() {
		super.tick();
		pitchTiltO = state.pitchTilt();
		rollTiltO = state.rollTilt();
		propAngleO = propAngle;

		if (isLocalInstanceAuthoritative()) {
			DroneInput in = getControllingPassenger() != null && input != null ? input : DroneInput.hover(getYRot());
			state = flight.step(state.withYaw(getYRot()), in); // withYaw picks up loads and authority hand-overs
			Vec3 intended = new Vec3(state.vx(), state.vy(), state.vz());
			Vec3 before = position();
			move(MoverType.SELF, intended);
			Vec3 actual = position().subtract(before);
			state = flight.afterCollision(state, intended.x, intended.y, intended.z, actual.x, actual.y, actual.z);
			setYRot((float) state.yaw());
			setDeltaMovement(state.vx(), state.vy(), state.vz());
		} else {
			// Spec §5.1: track what this side sees, so a hand-over (dismount) brakes from real speed.
			Vec3 seen = lastTickPos == null ? Vec3.ZERO : position().subtract(lastTickPos);
			state = flight.observe(state, seen.x, seen.y, seen.z, getYRot());
			setDeltaMovement(Vec3.ZERO);
		}
		lastTickPos = position();

		if (level().isClientSide()) {
			propAngle += (float) (PROP_BASE
					+ PROP_PER_TILT * (Math.abs(state.pitchTilt()) + Math.abs(state.rollTilt()))
					+ PROP_PER_CLIMB * Math.max(0, state.vy()));
			if (propAngle > PROP_WRAP) {
				propAngle -= PROP_WRAP;
				propAngleO -= PROP_WRAP;
			}
		}
	}

	/** Glide between position updates on the side that doesn't simulate, like boats. */
	@Override
	protected InterpolationHandler createInterpolationHandler() {
		return LinearInterpolationHandler.create(this, 3);
	}

	@Override
	public LivingEntity getControllingPassenger() {
		return getFirstPassenger() instanceof Player player ? player : null;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return getPassengers().isEmpty();
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
		if (player.isSecondaryUseActive()) {
			return InteractionResult.PASS;
		}
		if (!level().isClientSide()) {
			return player.startRiding(this) ? InteractionResult.SUCCESS : InteractionResult.PASS;
		}
		return InteractionResult.SUCCESS;
	}

	/** Step off beside the drone when there's room, otherwise vanilla's default (on top). */
	@Override
	public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
		Vec3 escape = getCollisionHorizontalEscapeVector(getBbWidth() * Mth.SQRT_OF_TWO, passenger.getBbWidth(), passenger.getYRot());
		Vec3 beside = new Vec3(getX() + escape.x, getY(), getZ() + escape.z);
		return DismountHelper.canDismountTo(level(), beside, passenger, Pose.STANDING)
				? beside
				: super.getDismountLocationForPassenger(passenger);
	}

	@Override
	protected Item getDropItem() {
		return ModItems.DRONE;
	}

	@Override
	public ItemStack getPickResult() {
		return new ItemStack(ModItems.DRONE);
	}

	@Override
	public boolean isPickable() {
		return !isRemoved();
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected double getDefaultGravity() {
		return 0.0;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}
}
