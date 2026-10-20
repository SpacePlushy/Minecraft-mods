package io.palmisano.rocket.entity;

import io.palmisano.rocket.flight.Engine;
import io.palmisano.rocket.flight.EngineEvent;
import io.palmisano.rocket.flight.ExhaustPlanner;
import io.palmisano.rocket.flight.Puff;
import io.palmisano.rocket.flight.RocketInput;
import io.palmisano.rocket.flight.RocketPhysics;
import io.palmisano.rocket.flight.RocketState;
import io.palmisano.rocket.flight.RocketTuning;
import io.palmisano.rocket.registry.ModItems;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Rideable rocket. Like boats and the drone, whichever side controls it (the rider's client, or the
 * server when unridden) steps {@link RocketPhysics} and moves; the other side interpolates and observes.
 * It never calls {@code applyEffectsFromBlocks()}, so lava, fire, cactus and cobwebs do nothing.
 */
public class RocketEntity extends VehicleEntity {
	private static final double GROUND_PROBE = 16; // vapor needs ground within this distance below the nozzle

	private final RocketPhysics physics = new RocketPhysics(RocketTuning.DEFAULT);
	private final ExhaustPlanner exhaust = new ExhaustPlanner(RocketTuning.DEFAULT);
	private final RandomGenerator effectsRandom = new SplittableRandom();
	private RocketState state = RocketState.resting(0);
	private RocketInput input;
	private Vec3 lastTickPos; // vehicle-move packets land between ticks, so xo/yo/zo can't see them
	private double pitchTiltO;
	private double rollTiltO;

	public RocketEntity(EntityType<? extends RocketEntity> type, Level level) {
		super(type, level);
		this.blocksBuilding = true;
	}

	/** Latest pilot input from the rider's client; read on the next authoritative tick. */
	public void setInput(RocketInput input) {
		this.input = input;
	}

	/** Engine phase as this side sees it (only the simulating side knows it; others read {@code OFF}). */
	public Engine getEngine() {
		return state.engine();
	}

	public float getPitchTilt(float partialTick) {
		return (float) Mth.lerp(partialTick, pitchTiltO, state.pitchTilt());
	}

	public float getRollTilt(float partialTick) {
		return (float) Mth.lerp(partialTick, rollTiltO, state.rollTilt());
	}

	@Override
	public void tick() {
		super.tick();
		pitchTiltO = state.pitchTilt();
		rollTiltO = state.rollTilt();
		if (getControllingPassenger() == null) {
			input = null; // a later rider starts from their own keys, not the last rider's
		}

		if (isLocalInstanceAuthoritative()) {
			RocketState previous = state;
			RocketInput in = getControllingPassenger() != null && input != null ? input : RocketInput.idle(getYRot());
			state = physics.step(state.withYaw(getYRot()), in, onGround()); // withYaw picks up loads and hand-overs
			Vec3 intended = new Vec3(state.vx(), state.vy(), state.vz());
			Vec3 before = position();
			move(MoverType.SELF, intended);
			Vec3 actual = position().subtract(before);
			state = physics.afterCollision(state, intended.x, intended.y, intended.z, actual.x, actual.y, actual.z);
			setYRot((float) state.yaw());
			setDeltaMovement(state.vx(), state.vy(), state.vz());
			if (level().isClientSide()) { // the rider's client: the only side that knows the engine
				emitExhaust();
				playEngineSounds(previous);
			}
		} else {
			// Spec §6.1: track what this side sees, so a hand-over (dismount) keeps the rocket's momentum.
			Vec3 seen = lastTickPos == null ? Vec3.ZERO : position().subtract(lastTickPos);
			state = physics.observe(state, seen.x, seen.y, seen.z, getYRot());
			setDeltaMovement(Vec3.ZERO);
		}
		lastTickPos = position();
	}

	/** Spec §8: vanilla particles for each puff the planner asks for. */
	private void emitExhaust() {
		double height = nozzleHeight();
		for (Puff p : exhaust.plan(state, height, effectsRandom)) {
			level().addParticle(particle(p.kind()), getX() + p.dx(), getY() + p.dy(), getZ() + p.dz(), p.vx(), p.vy(), p.vz());
		}
	}

	private static ParticleOptions particle(Puff.Kind kind) {
		return switch (kind) {
			case VAPOR -> ParticleTypes.CLOUD;
			case BILLOW -> ParticleTypes.EXPLOSION; // big white-gray puffs: the cloud hugging the pad
			case FLAME -> ParticleTypes.FLAME;
			case TRAIL -> ParticleTypes.LARGE_SMOKE;
		};
	}

	/** Height of the nozzle (the base) above the first solid block below it, or infinity if none is near. */
	private double nozzleHeight() {
		Vec3 from = position().add(0, 0.1, 0); // start just above the base, so a rocket on the pad sees the pad
		BlockHitResult hit = level().clip(new ClipContext(from, from.add(0, -GROUND_PROBE, 0),
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		return hit.getType() == HitResult.Type.MISS ? Double.POSITIVE_INFINITY : Math.max(0, getY() - hit.getLocation().y);
	}

	/** Spec §8: a steam hiss through ignition, a roar at liftoff, a crackling burn while lit. */
	private void playEngineSounds(RocketState previous) {
		EngineEvent.between(previous.engine(), state.engine()).ifPresent(event -> {
			switch (event) {
				case IGNITION_STARTED -> sound(SoundEvents.FIRE_EXTINGUISH, 1, 0.6f);
				case LIFTOFF -> sound(SoundEvents.FIREWORK_ROCKET_LAUNCH, 3, 0.5f);
				case ABORTED, SHUTDOWN -> sound(SoundEvents.FIRE_EXTINGUISH, 1, 1.2f);
			}
		});
		if (state.engine() == Engine.IGNITION && state.engineTicks() > 0 && state.engineTicks() % 10 == 0) {
			sound(SoundEvents.FIRE_EXTINGUISH, 1, 0.6f);
		}
		if (state.engine() == Engine.BURNING && tickCount % 8 == 0) {
			sound(SoundEvents.FIRE_AMBIENT, 0.4f + (float) state.throttle(), 0.5f);
		}
	}

	private void sound(SoundEvent event, float volume, float pitch) {
		level().playLocalSound(getX(), getY(), getZ(), event, SoundSource.NEUTRAL, volume, pitch, false);
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

	/** Seat the rider facing the rocket's way, so their seated legs stay inside the cabin. */
	@Override
	protected void positionRider(Entity passenger, MoveFunction moveFunction) {
		super.positionRider(passenger, moveFunction);
		if (passenger instanceof LivingEntity living) {
			living.setYBodyRot(getYRot());
		}
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

	/** Step off beside the rocket when there's room, otherwise vanilla's default (on top). */
	@Override
	public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
		Vec3 escape = getCollisionHorizontalEscapeVector(getBbWidth() * Mth.SQRT_OF_TWO, passenger.getBbWidth(), passenger.getYRot());
		Vec3 beside = new Vec3(getX() + escape.x, getY(), getZ() + escape.z);
		return DismountHelper.canDismountTo(level(), beside, passenger, Pose.STANDING)
				? beside
				: super.getDismountLocationForPassenger(passenger);
	}

	/** Hard landings never hurt the rider (spec §9). */
	@Override
	public boolean causeFallDamage(double fallDistance, float multiplier, DamageSource source) {
		return false;
	}

	@Override
	protected Item getDropItem() {
		return ModItems.ROCKET;
	}

	@Override
	public ItemStack getPickResult() {
		return new ItemStack(ModItems.ROCKET);
	}

	@Override
	public boolean isPickable() {
		return !isRemoved();
	}

	/** The rider's eyes are inside the hitbox: let their crosshair pass through, or a left-click removes the rocket. */
	@Override
	public boolean canBePickedFromInside() {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected double getDefaultGravity() {
		return 0.0; // RocketPhysics owns gravity
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}
}
