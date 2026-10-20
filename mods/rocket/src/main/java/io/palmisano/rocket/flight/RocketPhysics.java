package io.palmisano.rocket.flight;

/**
 * Rocket physics, one tick at a time (spec §7). Pure Java: no Minecraft types.
 * Yaw 0 faces +Z; forward = (-sin yaw, cos yaw), right = (-cos yaw, -sin yaw) in (x, z).
 */
public final class RocketPhysics {
	private static final double IDLE_THROTTLE = 0.01;

	private final RocketTuning t;

	public RocketPhysics(RocketTuning tuning) {
		this.t = tuning;
	}

	public RocketState step(RocketState s, RocketInput in, boolean onGround) {
		double yaw = s.yaw();
		double pitch = s.pitchTilt();
		double roll = s.rollTilt();

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

		// 8. Ground: never sink into the pad between collisions.
		if (onGround) {
			vy = Math.max(vy, 0.0);
		}

		return new RocketState(vx, vy, vz, yaw, pitch, roll, throttle, engine, ticks);
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
