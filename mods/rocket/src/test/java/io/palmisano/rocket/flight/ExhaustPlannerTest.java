package io.palmisano.rocket.flight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ExhaustPlannerTest {
	private static final ExhaustPlanner P = new ExhaustPlanner(RocketTuning.DEFAULT);
	private static final double NO_GROUND = Double.POSITIVE_INFINITY;

	private static RocketState engine(Engine engine, double throttle, double yaw, double pitchTilt) {
		return new RocketState(0, 0, 0, yaw, pitchTilt, 0, throttle, engine, 0);
	}

	private static List<Puff> plan(RocketState s, double height) {
		return P.plan(s, height, new Random(42));
	}

	private static long count(List<Puff> puffs, Puff.Kind kind) {
		return puffs.stream().filter(p -> p.kind() == kind).count();
	}

	@Test
	void offEmitsNothing() {
		assertTrue(plan(RocketState.resting(0), 0).isEmpty());
	}

	@Test
	void ignitionEmitsVaporAndBillow() {
		List<Puff> puffs = plan(engine(Engine.IGNITION, 0, 0, 0), 0);
		assertEquals(6, count(puffs, Puff.Kind.VAPOR));
		assertEquals(2, count(puffs, Puff.Kind.BILLOW));
		assertEquals(0, count(puffs, Puff.Kind.FLAME));
		assertEquals(0, count(puffs, Puff.Kind.TRAIL));
	}

	@Test
	void vaporRollsOutAlongTheGround() { // the launch clouds spread out across the pad, away from the rocket
		List<Puff> vapor = plan(engine(Engine.IGNITION, 0, 0, 0), 0).stream().filter(p -> p.kind() == Puff.Kind.VAPOR).toList();
		assertTrue(!vapor.isEmpty(), "no vapor at ignition");
		for (Puff p : vapor) {
			assertEquals(0.1, p.dy(), 1e-9);
			double speed = Math.hypot(p.vx(), p.vz());
			assertTrue(speed >= 0.25 && speed <= 0.5, "speed " + speed);
			assertTrue(p.dx() * p.vx() + p.dz() * p.vz() > 0, "vapor heading inward: " + p);
			assertTrue(p.vy() >= 0.02 && p.vy() <= 0.05, "vy " + p.vy());
		}
	}

	@Test
	void flameCountFollowsThrottle() {
		List<Puff> idle = plan(engine(Engine.BURNING, 0, 0, 0), NO_GROUND);
		assertEquals(1, count(idle, Puff.Kind.FLAME));
		assertEquals(0, count(idle, Puff.Kind.TRAIL));
		List<Puff> full = plan(engine(Engine.BURNING, 1, 0, 0), NO_GROUND);
		assertEquals(6, count(full, Puff.Kind.FLAME));
		assertEquals(3, count(full, Puff.Kind.TRAIL));
	}

	@Test
	void noVaporHighUp() {
		List<Puff> puffs = plan(engine(Engine.BURNING, 1, 0, 0), 20);
		assertEquals(0, count(puffs, Puff.Kind.VAPOR));
		assertEquals(0, count(puffs, Puff.Kind.BILLOW));
	}

	@Test
	void vaporNearTheGroundFadesWithHeight() {
		List<Puff> onPad = plan(engine(Engine.BURNING, 1, 0, 0), 0);
		assertEquals(8, count(onPad, Puff.Kind.VAPOR));
		assertEquals(1, count(onPad, Puff.Kind.BILLOW));
		List<Puff> rising = plan(engine(Engine.BURNING, 1, 0, 0), 4);
		assertEquals(4, count(rising, Puff.Kind.VAPOR));
		rising.stream().filter(p -> p.kind() == Puff.Kind.VAPOR).forEach(p -> assertEquals(-3.9, p.dy(), 1e-9));
		assertEquals(0, count(plan(engine(Engine.BURNING, 0.2, 0, 0), 0), Puff.Kind.VAPOR));
	}

	@Test
	void flameShootsOppositeTheNose() {
		plan(engine(Engine.BURNING, 1, 0, 0), NO_GROUND).stream().filter(p -> p.kind() == Puff.Kind.FLAME)
				.forEach(p -> assertTrue(p.vy() < 0, "upright flame going up: " + p));
		double meanVz = plan(engine(Engine.BURNING, 1, 0, 20), NO_GROUND).stream()
				.filter(p -> p.kind() == Puff.Kind.FLAME).mapToDouble(Puff::vz).average().orElseThrow();
		assertTrue(meanVz < 0, "nose tipped toward +Z, flame should go -Z: " + meanVz);
	}
}
