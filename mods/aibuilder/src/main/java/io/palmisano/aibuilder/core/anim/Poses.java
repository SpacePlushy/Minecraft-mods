package io.palmisano.aibuilder.core.anim;

import java.util.Map;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.Pos;

/**
 * Where a moving part is at a time (spec v2 §4.3), and what each of its displays is told. Everything is in
 * world-aligned axes from the build's origin: a local vector v is {@code A·v}, with {@code A} turning the frame's right
 * and forward into the facing's (a mirror image, as the local frame is), and a local point in block-centre units is
 * {@code A·p + ½}. So the numbers stay small, and a rotation is a world rotation: clockwise looking along its axis,
 * in every facing.
 */
public final class Poses {
	private static final Vec3 HALF = new Vec3(0.5, 0.5, 0.5);

	private Poses() {
	}

	/**
	 * The part's motion {@code ticks} after its build began: its own, then its parent's, and so on up. An unknown
	 * parent (or a chain past the depth limit) ends the chain there.
	 */
	public static Rigid rigid(String part, Map<String, MovingPart> parts, double ticks, Facing facing) {
		Rigid pose = Rigid.IDENTITY;
		String at = part;
		for (int depth = 0; !at.isEmpty() && depth <= Tunables.PART_DEPTH_MAX; depth++) {
			MovingPart p = parts.get(at);
			if (p == null) {
				break;
			}
			pose = pose.then(own(p, ticks, facing));
			at = p.parent();
		}
		return pose;
	}

	/** A display standing at its block's corner: drawn through this, its cube goes where the part's motion puts the block's. */
	public static DisplayPose display(Rigid pose, Pos localBlock, Facing facing) {
		Vec3 corner = aligned(new Vec3(localBlock.x(), localBlock.y(), localBlock.z()), facing);
		return new DisplayPose(pose.apply(corner).minus(corner), pose.r());
	}

	/**
	 * How often the part is given a new pose: every {@link Tunables#ANIMATION_STEP_TICKS} ticks, or every 2 or every
	 * tick when its motions (its parents' included) would turn it more than {@link Tunables#SMOOTH_TURN_DEGREES}
	 * between two poses. The game glides a display's corner in a straight line from one pose to the next while it turns
	 * it, so a part turning far between them draws in for a moment.
	 */
	public static int stepTicks(String part, Map<String, MovingPart> parts) {
		double degrees = 0;
		String at = part;
		for (int depth = 0; !at.isEmpty() && depth <= Tunables.PART_DEPTH_MAX; depth++) {
			MovingPart p = parts.get(at);
			if (p == null) {
				break;
			}
			for (Motion motion : p.motions()) {
				degrees += stepDegrees(motion);
			}
			at = p.parent();
		}
		int ticks = Tunables.ANIMATION_STEP_TICKS;
		while (ticks > 1 && degrees * ticks / Tunables.ANIMATION_STEP_TICKS > Tunables.SMOOTH_TURN_DEGREES) {
			ticks /= 2;
		}
		return ticks;
	}

	/** The most a motion turns between two poses (one every {@link Tunables#ANIMATION_STEP_TICKS}). */
	public static double stepDegrees(Motion motion) {
		return switch (motion) {
			case Motion.Swing s -> swingStep(s.from(), s.to(), s.period());
			case Motion.Spin s -> 360.0 * Tunables.ANIMATION_STEP_TICKS / Math.abs(s.period());
			case Motion.Bob b -> 0;
		};
	}

	/** A swing turns fastest half-way, at |to − from|·π/period degrees a tick. */
	static double swingStep(double from, double to, int period) {
		return Math.abs(to - from) * Math.PI * Tunables.ANIMATION_STEP_TICKS / period;
	}

	/** The part's own motion: its rotations about the pivot, in order, then its bobs. */
	private static Rigid own(MovingPart part, double ticks, Facing facing) {
		Quat r = Quat.IDENTITY;
		Vec3 bob = Vec3.ZERO;
		for (Motion motion : part.motions()) {
			switch (motion) {
				case Motion.Swing s -> r = Quat.axisAngle(direction(s.axis(), facing), s.from() + (s.to() - s.from()) * wave(ticks, s.period(), s.phase()))
						.times(r);
				case Motion.Spin s -> r = Quat.axisAngle(direction(s.axis(), facing), 360 * (ticks / s.period() + s.phase())).times(r);
				case Motion.Bob b -> bob = bob.plus(direction(b.direction(), facing).times(b.distance() * wave(ticks, b.period(), b.phase())));
			}
		}
		Vec3 pivot = aligned(part.pivot(), facing).plus(HALF);
		return new Rigid(r, pivot.minus(r.rotate(pivot)).plus(bob));
	}

	/** 0 at the start of a cycle, 1 half-way, 0 at its end: (1 − cos 2π(t/period + phase)) / 2. */
	private static double wave(double ticks, int period, double phase) {
		return (1 - Math.cos(2 * Math.PI * (ticks / period + phase))) / 2;
	}

	/** A relative word as a unit vector in world-aligned axes. */
	private static Vec3 direction(String word, Facing facing) {
		Vec3 local = switch (word) {
			case "right" -> new Vec3(1, 0, 0);
			case "left" -> new Vec3(-1, 0, 0);
			case "up" -> new Vec3(0, 1, 0);
			case "down" -> new Vec3(0, -1, 0);
			case "forward" -> new Vec3(0, 0, 1);
			case "back" -> new Vec3(0, 0, -1);
			default -> throw new IllegalArgumentException("not a direction: " + word);
		};
		return aligned(local, facing);
	}

	/** {@code A·v}: the frame's right and forward turned into the facing's, as {@code LocalFrame.toWorld} does. */
	private static Vec3 aligned(Vec3 v, Facing facing) {
		return new Vec3(v.x() * facing.rightX() + v.z() * facing.forwardX(), v.y(), v.x() * facing.rightZ() + v.z() * facing.forwardZ());
	}
}
