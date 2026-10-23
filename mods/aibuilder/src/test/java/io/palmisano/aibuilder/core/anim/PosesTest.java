package io.palmisano.aibuilder.core.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import org.junit.jupiter.api.Test;

/**
 * Where a moving part is at a given time (spec v2 §4.2, §4.3), in world-aligned axes from the build's origin, and
 * what each of its displays is told.
 */
class PosesTest {
	private static final double EPS = 1e-9;

	private static MovingPart part(String name, Vec3 pivot, String parent, Motion... motions) {
		return new MovingPart(name, pivot, List.of(motions), parent);
	}

	private static Rigid pose(MovingPart part, double ticks, Facing facing) {
		return Poses.rigid(part.name(), Map.of(part.name(), part), ticks, facing);
	}

	/** Where a local point (block-centre units) is, in world-aligned axes from the origin: A·p + ½. */
	private static Vec3 world(Vec3 local, Facing facing) {
		return new Vec3(local.x() * facing.rightX() + local.z() * facing.forwardX() + 0.5, local.y() + 0.5,
				local.x() * facing.rightZ() + local.z() * facing.forwardZ() + 0.5);
	}

	private static void assertClose(Vec3 expected, Vec3 actual) {
		assertTrue(Math.abs(expected.x() - actual.x()) < EPS && Math.abs(expected.y() - actual.y()) < EPS
				&& Math.abs(expected.z() - actual.z()) < EPS, "expected " + expected + ", got " + actual);
	}

	/** Where the part puts a local point. */
	private static Vec3 moved(MovingPart part, double ticks, Facing facing, Vec3 local) {
		return pose(part, ticks, facing).apply(world(local, facing));
	}

	@Test
	void swingStartsAtFromAndReachesToAtHalfAPeriod() {
		// A swing about up: a point one block forward of the pivot shows the angle.
		MovingPart arm = part("arm", new Vec3(0, 0, 0), "", new Motion.Swing("up", 10, 50, 40, 0));
		for (double[] at : new double[][] {{0, 10}, {10, 30}, {20, 50}, {30, 30}, {40, 10}}) {
			Rigid expected = new Rigid(Quat.axisAngle(new Vec3(0, 1, 0), at[1]), new Vec3(0, 0, 0));
			Vec3 pivot = world(new Vec3(0, 0, 0), Facing.SOUTH);
			Vec3 point = world(new Vec3(0, 0, 1), Facing.SOUTH);
			Vec3 want = expected.apply(point.minus(pivot)).plus(pivot);
			assertClose(want, moved(arm, at[0], Facing.SOUTH, new Vec3(0, 0, 1)));
		}
	}

	@Test
	void aSpinQuarterTurns() {
		MovingPart wheel = part("wheel", new Vec3(0, 0, 0), "", new Motion.Spin("forward", 100, 0));
		Vec3 up = new Vec3(0, 1, 0);
		// About forward, clockwise looking along it: up → right → down → left → up.
		assertClose(world(up, Facing.SOUTH), moved(wheel, 0, Facing.SOUTH, up));
		assertClose(world(new Vec3(1, 0, 0), Facing.SOUTH), moved(wheel, 25, Facing.SOUTH, up));
		assertClose(world(new Vec3(0, -1, 0), Facing.SOUTH), moved(wheel, 50, Facing.SOUTH, up));
		assertClose(world(new Vec3(-1, 0, 0), Facing.SOUTH), moved(wheel, 75, Facing.SOUTH, up));
		assertClose(world(up, Facing.SOUTH), moved(wheel, 100, Facing.SOUTH, up));
		// A negative period turns the other way.
		MovingPart back = part("back", new Vec3(0, 0, 0), "", new Motion.Spin("forward", -100, 0));
		assertClose(world(new Vec3(-1, 0, 0), Facing.SOUTH), moved(back, 25, Facing.SOUTH, up));
	}

	@Test
	void bobGoesOutAndBack() {
		MovingPart head = part("head", new Vec3(0, 0, 0), "", new Motion.Bob("up", 2, 40, 0));
		Vec3 at = new Vec3(3, 4, 5);
		assertClose(world(at, Facing.EAST), moved(head, 0, Facing.EAST, at));
		assertClose(world(new Vec3(3, 5, 5), Facing.EAST), moved(head, 10, Facing.EAST, at));
		assertClose(world(new Vec3(3, 6, 5), Facing.EAST), moved(head, 20, Facing.EAST, at));
		assertClose(world(at, Facing.EAST), moved(head, 40, Facing.EAST, at));
		MovingPart sideways = part("s", new Vec3(0, 0, 0), "", new Motion.Bob("right", 2, 40, 0));
		assertClose(world(new Vec3(5, 4, 5), Facing.EAST), moved(sideways, 20, Facing.EAST, at));
	}

	@Test
	void phaseShiftsTheCycle() {
		Vec3 tip = new Vec3(4, 0, 0);
		MovingPart early = part("w", new Vec3(0, 0, 0), "", new Motion.Swing("forward", -20, 60, 40, 0.5));
		MovingPart late = part("w", new Vec3(0, 0, 0), "", new Motion.Swing("forward", -20, 60, 40, 0));
		assertClose(moved(late, 20, Facing.NORTH, tip), moved(early, 0, Facing.NORTH, tip));
		assertClose(moved(late, 30, Facing.NORTH, tip), moved(early, 10, Facing.NORTH, tip));
	}

	@Test
	void aChildRidesItsParent() {
		MovingPart body = part("body", new Vec3(0, 0, 0), "", new Motion.Bob("up", 2, 40, 0));
		MovingPart wing = part("wing", new Vec3(1, 3, 0), "body", new Motion.Swing("forward", 0, 90, 40, 0));
		MovingPart rider = part("rider", new Vec3(0, 0, 0), "body");
		Map<String, MovingPart> parts = Map.of("body", body, "wing", wing, "rider", rider);
		for (double t : new double[] {0, 7, 20, 33}) {
			Rigid bodyPose = Poses.rigid("body", parts, t, Facing.WEST);
			Rigid wingOwn = Poses.rigid("wing", Map.of("wing", new MovingPart("wing", wing.pivot(), wing.motions(), "")), t, Facing.WEST);
			Vec3 tip = world(new Vec3(5, 3, 0), Facing.WEST);
			assertClose(bodyPose.apply(wingOwn.apply(tip)), Poses.rigid("wing", parts, t, Facing.WEST).apply(tip));
			// A part with no motion of its own goes just where its parent does.
			assertClose(bodyPose.apply(tip), Poses.rigid("rider", parts, t, Facing.WEST).apply(tip));
		}
	}

	/**
	 * The game glides a display's corner in a straight line between two poses while it turns, so a part that turns far
	 * between them draws in for a moment: a fast part is posed every 2 ticks or every tick, and a part riding it as
	 * often.
	 */
	@Test
	void aFastPartIsPosedMoreOften() {
		Map<String, MovingPart> parts = Map.of(
				"slow", part("slow", Vec3.ZERO, "", new Motion.Swing("forward", -30, 40, 40, 0)),
				"quick", part("quick", Vec3.ZERO, "", new Motion.Spin("up", 80, 0)),
				"fast", part("fast", Vec3.ZERO, "", new Motion.Spin("up", 30, 0)),
				"fastest", part("fastest", Vec3.ZERO, "", new Motion.Spin("up", -10, 0)),
				"rider", part("rider", new Vec3(1, 0, 0), "fast", new Motion.Swing("up", 0, 10, 400, 0)),
				"both", part("both", Vec3.ZERO, "", new Motion.Spin("up", 72, 0), new Motion.Spin("forward", 72, 0)),
				"bobbing", part("bobbing", Vec3.ZERO, "", new Motion.Bob("up", 16, 10, 0)));
		assertEquals(4, Poses.stepTicks("slow", parts), "22° in 4 ticks");
		assertEquals(4, Poses.stepTicks("quick", parts), "18° in 4 ticks");
		assertEquals(2, Poses.stepTicks("fast", parts), "48° in 4 ticks");
		assertEquals(1, Poses.stepTicks("fastest", parts), "144° in 4 ticks");
		assertEquals(2, Poses.stepTicks("rider", parts), "it rides a part posed every 2");
		assertEquals(2, Poses.stepTicks("both", parts), "two 20° turns add up");
		assertEquals(4, Poses.stepTicks("bobbing", parts), "a bob glides straight");
	}

	/** A dragon circling a tower: a spin about a pivot outside the part keeps it at its distance, going round. */
	@Test
	void aPivotOutsideThePartCirclesIt() {
		MovingPart dragon = part("dragon", new Vec3(0, 0, 0), "", new Motion.Spin("up", 100, 0));
		Vec3 at = new Vec3(5, 0, 0);
		Vec3 pivot = world(new Vec3(0, 0, 0), Facing.SOUTH);
		for (double t = 0; t <= 100; t += 12.5) {
			assertEquals(5, distance(moved(dragon, t, Facing.SOUTH, at), pivot), EPS);
		}
		// About up, clockwise looking up (anticlockwise from above): a point on the right comes round to the front.
		assertClose(world(new Vec3(0, 0, 5), Facing.SOUTH), moved(dragon, 25, Facing.SOUTH, at));
	}

	/**
	 * The local frame is a mirror image of the world's axes, so the sign rule is about what's seen: a positive angle
	 * turns clockwise looking along the axis. Turned +90° about forward, a point above the pivot lands on its right, in
	 * every facing (Review Focus 1).
	 */
	@Test
	void theSignRuleHoldsInEveryFacing() {
		for (Facing facing : Facing.values()) {
			Vec3 pivot = new Vec3(2, 5, -3);
			MovingPart quarter = part("q", pivot, "", new Motion.Spin("forward", 400, 0.25));
			Vec3 above = pivot.plus(new Vec3(0, 1, 0));
			assertClose(world(pivot.plus(new Vec3(1, 0, 0)), facing), moved(quarter, 0, facing, above));
			// The same about up: a point in front of the pivot swings to its left.
			MovingPart yaw = part("y", pivot, "", new Motion.Spin("up", 400, 0.25));
			assertClose(world(pivot.plus(new Vec3(-1, 0, 0)), facing), moved(yaw, 0, facing, pivot.plus(new Vec3(0, 0, 1))));
		}
	}

	/**
	 * A display stands at its block's corner and draws the block's cube through its transformation: the eight corners
	 * must land where the part's motion puts the block's corners, and at rest the display sits exactly on its block.
	 */
	@Test
	void aDisplaysCornersGoWhereTheBlocksWould() {
		MovingPart wing = part("wing", new Vec3(0.5, 4, 2), "", new Motion.Swing("back", -30, 45, 40, 0), new Motion.Bob("up", 1, 60, 0));
		for (Facing facing : Facing.values()) {
			LocalFrame frame = new LocalFrame(new Pos(0, 0, 0), facing);
			Pos block = new Pos(3, 4, 2);
			Pos corner = frame.toWorld(block);
			Vec3 e = new Vec3(corner.x(), corner.y(), corner.z());
			for (double t : new double[] {0, 9, 23}) {
				Rigid pose = Poses.rigid("wing", Map.of("wing", wing), t, facing);
				DisplayPose display = Poses.display(pose, block, facing);
				for (int i = 0; i < 8; i++) {
					Vec3 m = new Vec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
					assertClose(pose.apply(e.plus(m)), e.plus(display.translation()).plus(display.rotation().rotate(m)));
				}
			}
			DisplayPose rest = Poses.display(Rigid.IDENTITY, block, facing);
			assertClose(new Vec3(0, 0, 0), rest.translation());
		}
	}

	private static double distance(Vec3 a, Vec3 b) {
		Vec3 d = a.minus(b);
		return Math.sqrt(d.x() * d.x() + d.y() * d.y() + d.z() * d.z());
	}
}
