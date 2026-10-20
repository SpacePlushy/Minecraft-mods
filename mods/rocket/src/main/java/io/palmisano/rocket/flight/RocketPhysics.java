package io.palmisano.rocket.flight;

/**
 * Rocket physics, one tick at a time (spec §7). Pure Java: no Minecraft types.
 * Yaw 0 faces +Z; forward = (-sin yaw, cos yaw), right = (-cos yaw, -sin yaw) in (x, z).
 */
public final class RocketPhysics {
	private static final double IDLE_THROTTLE = 0.01;
	// Observer-side filtering for observe(); not flight feel, so not in RocketTuning.
	private static final double OBSERVED_SMOOTHING = 0.5;
	private static final double LEVEL_OUT = 0.8;
	private static final double TELEPORT_DISTANCE = 8; // a one-tick move longer than this isn't flight

	private final RocketTuning t;

	public RocketPhysics(RocketTuning tuning) {
		this.t = tuning;
	}

	public RocketState step(RocketState s, RocketInput in, boolean onGround) {
		// 1. Yaw eases toward the target, the short way round, capped per tick.
		double yaw = s.yaw() + clamp(wrapDegrees(in.targetYaw() - s.yaw()) * t.yawResponse(), t.maxYawRate());

		// 2. Tilt follows the keys and self-rights when they're released; it can't lean on the pad.
		double targetPitch = onGround ? 0 : in.forward() * t.maxTilt();
		double targetRoll = onGround ? 0 : in.strafe() * t.maxTilt();
		double pitch = s.pitchTilt() + (targetPitch - s.pitchTilt()) * t.tiltResponse();
		double roll = s.rollTilt() + (targetRoll - s.rollTilt()) * t.tiltResponse();

		// 3. Engine: hold Space on the pad to ignite; let go during ignition to abort; mid-air lights at once.
		Engine engine = s.engine();
		int ticks = s.engineTicks();
		switch (engine) {
			case OFF -> {
				if (in.throttleUp()) {
					engine = onGround ? Engine.IGNITION : Engine.BURNING;
					ticks = 0;
				}
			}
			case IGNITION -> {
				if (!in.throttleUp()) {
					engine = Engine.OFF;
					ticks = 0;
				} else if (++ticks >= t.ignitionTicks()) {
					engine = Engine.BURNING;
					ticks = 0;
				}
			}
			case BURNING -> {
			}
		}

		// 4. Throttle spools toward full (Space held) or idle; zero unless burning.
		double throttle = 0;
		if (engine == Engine.BURNING) {
			throttle = s.throttle() + ((in.throttleUp() ? 1 : 0) - s.throttle()) * t.throttleResponse();
			if (s.engine() == Engine.BURNING) {
				boolean landedIdle = onGround && !in.throttleUp() && throttle < IDLE_THROTTLE;
				ticks = landedIdle ? ticks + 1 : 0;
				if (ticks >= t.shutdownTicks()) {
					engine = Engine.OFF;
					ticks = 0;
					throttle = 0;
				}
			}
		}

		// 5. Thrust along the nose.
		Vec n = nose(yaw, pitch, roll);
		double accel = throttle * t.maxThrust();
		double vx = s.vx() + n.x() * accel;
		double vy = s.vy() + n.y() * accel;
		double vz = s.vz() + n.z() * accel;

		// 6. Gravity.
		vy -= t.gravity();

		// 7. Drag.
		double keep = 1 - t.drag();
		vx *= keep;
		vy *= keep;
		vz *= keep;

		// 8. Ground: never sink into the pad between collisions; friction stops any slide.
		if (onGround) {
			vy = Math.max(vy, 0.0);
			vx *= t.groundFriction();
			vz *= t.groundFriction();
			if (Math.hypot(vx, vz) < t.settleSpeed()) {
				vx = 0.0;
				vz = 0.0;
			}
		}

		return new RocketState(vx, vy, vz, yaw, pitch, roll, throttle, engine, ticks);
	}

	/** Bump and stop: zero the velocity on every axis where collision cut the intended movement. */
	public RocketState afterCollision(RocketState s, double intendedX, double intendedY, double intendedZ,
			double actualX, double actualY, double actualZ) {
		return new RocketState(
				blocked(intendedX, actualX) ? 0.0 : s.vx(),
				blocked(intendedY, actualY) ? 0.0 : s.vy(),
				blocked(intendedZ, actualZ) ? 0.0 : s.vz(),
				s.yaw(), s.pitchTilt(), s.rollTilt(), s.throttle(), s.engine(), s.engineTicks());
	}

	/**
	 * State for a rocket this side doesn't simulate, given how far it moved since last tick (spec §6.1).
	 * Velocity follows the seen movement, smoothed so a tick without a vehicle-move packet doesn't read
	 * as a stop; whichever side takes over then starts from real speed. The engine reads as off (only
	 * the simulating side knows it) and the tilt levels out. A jump far beyond flying speed is a
	 * teleport and reads as rest.
	 */
	public RocketState observe(RocketState s, double dx, double dy, double dz, double yaw) {
		if (Math.sqrt(dx * dx + dy * dy + dz * dz) > TELEPORT_DISTANCE) {
			return new RocketState(0, 0, 0, yaw, s.pitchTilt() * LEVEL_OUT, s.rollTilt() * LEVEL_OUT, 0, Engine.OFF, 0);
		}
		return new RocketState(
				s.vx() + (dx - s.vx()) * OBSERVED_SMOOTHING,
				s.vy() + (dy - s.vy()) * OBSERVED_SMOOTHING,
				s.vz() + (dz - s.vz()) * OBSERVED_SMOOTHING,
				yaw, s.pitchTilt() * LEVEL_OUT, s.rollTilt() * LEVEL_OUT, 0, Engine.OFF, 0);
	}

	private static boolean blocked(double intended, double actual) {
		return Math.abs(actual - intended) > 1e-7;
	}

	private static double clamp(double value, double limit) {
		return Math.max(-limit, Math.min(limit, value));
	}

	/** Maps an angle to (-180, 180]. */
	private static double wrapDegrees(double degrees) {
		double d = degrees % 360;
		if (d <= -180) d += 360;
		if (d > 180) d -= 360;
		return d;
	}

	/** Unit vector along the rocket's nose (spec §7 step 5). */
	public static Vec nose(double yaw, double pitchTilt, double rollTilt) {
		double rad = Math.toRadians(yaw);
		double fx = -Math.sin(rad), fz = Math.cos(rad);
		double rx = -Math.cos(rad), rz = -Math.sin(rad);
		double sp = Math.sin(Math.toRadians(pitchTilt));
		double sr = Math.sin(Math.toRadians(rollTilt));
		double up = Math.sqrt(Math.max(0, 1 - sp * sp - sr * sr));
		return new Vec(fx * sp + rx * sr, up, fz * sp + rz * sr);
	}
}
