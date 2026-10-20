package io.palmisano.rocket.flight;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Decides each tick's exhaust particles (spec §8): launch vapor rolling out along the ground during
 * ignition and while the nozzle is low, flame and a smoke trail while burning. Pure Java: no Minecraft types.
 */
public final class ExhaustPlanner {
	private static final double VAPOR_START_RADIUS = 0.7; // just outside the rocket's base
	private static final double ABOVE_GROUND = 0.1;

	private final RocketTuning t;

	public ExhaustPlanner(RocketTuning tuning) {
		this.t = tuning;
	}

	/** {@code nozzleHeight}: height of the nozzle above the ground below it, or infinity if none is near. */
	public List<Puff> plan(RocketState s, double nozzleHeight, RandomGenerator random) {
		List<Puff> puffs = new ArrayList<>();
		double groundDy = Double.isInfinite(nozzleHeight) ? ABOVE_GROUND : -nozzleHeight + ABOVE_GROUND;
		switch (s.engine()) {
			case OFF -> {
			}
			case IGNITION -> {
				addVapor(puffs, 6, groundDy, random);
				addBillow(puffs, 2, groundDy, random);
			}
			case BURNING -> {
				Vec nose = RocketPhysics.nose(s.yaw(), s.pitchTilt(), s.rollTilt());
				addAlongExhaust(puffs, Puff.Kind.FLAME, (int) Math.round(1 + 5 * s.throttle()), nose, 0.15, 0.3, 0.5, 0.05, random);
				addAlongExhaust(puffs, Puff.Kind.TRAIL, (int) Math.round(3 * s.throttle()), nose, 0.2, 0.1, 0.2, 0, random);
				if (nozzleHeight < t.vaporHeight() && s.throttle() > 0.3) {
					addVapor(puffs, (int) Math.round(8 * (1 - nozzleHeight / t.vaporHeight())), groundDy, random);
					addBillow(puffs, 1, groundDy, random);
				}
			}
		}
		return puffs;
	}

	private static void addVapor(List<Puff> puffs, int count, double dy, RandomGenerator random) {
		for (int i = 0; i < count; i++) {
			double angle = random.nextDouble(2 * Math.PI);
			double cos = Math.cos(angle), sin = Math.sin(angle);
			double speed = random.nextDouble(0.25, 0.5);
			puffs.add(new Puff(Puff.Kind.VAPOR, cos * VAPOR_START_RADIUS, dy, sin * VAPOR_START_RADIUS,
					cos * speed, random.nextDouble(0.02, 0.05), sin * speed));
		}
	}

	private static void addBillow(List<Puff> puffs, int count, double groundDy, RandomGenerator random) {
		for (int i = 0; i < count; i++) {
			puffs.add(new Puff(Puff.Kind.BILLOW, random.nextDouble(-0.5, 0.5), groundDy + 0.1, random.nextDouble(-0.5, 0.5),
					random.nextDouble(-0.03, 0.03), 0.02, random.nextDouble(-0.03, 0.03)));
		}
	}

	/** Puffs leaving the nozzle along -nose at a speed in [minSpeed, maxSpeed), with positional and velocity jitter. */
	private static void addAlongExhaust(List<Puff> puffs, Puff.Kind kind, int count, Vec nose, double spread,
			double minSpeed, double maxSpeed, double velocityJitter, RandomGenerator random) {
		for (int i = 0; i < count; i++) {
			double speed = random.nextDouble(minSpeed, maxSpeed);
			puffs.add(new Puff(kind,
					random.nextDouble(-spread, spread), -0.1, random.nextDouble(-spread, spread),
					-nose.x() * speed + jitter(velocityJitter, random),
					-nose.y() * speed + jitter(velocityJitter, random),
					-nose.z() * speed + jitter(velocityJitter, random)));
		}
	}

	private static double jitter(double amount, RandomGenerator random) {
		return amount == 0 ? 0 : random.nextDouble(-amount, amount);
	}
}
