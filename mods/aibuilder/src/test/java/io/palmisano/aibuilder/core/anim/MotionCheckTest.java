package io.palmisano.aibuilder.core.anim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.Pos;
import org.junit.jupiter.api.Test;

/** check_motion's answer (spec v2 §4.5): where each part goes over its cycle, and the solid blocks it passes through. */
class MotionCheckTest {
	/** A three-block wing off the top of a post (x 0, y 1 to 3) to the right, hinged on the post's top right edge. */
	private static final List<Pos> WING = List.of(new Pos(1, 3, 4), new Pos(2, 3, 4), new Pos(3, 3, 4));
	private static final Vec3 HINGE = new Vec3(0.5, 3.5, 4);
	private static final Set<Pos> POST = Set.of(new Pos(0, 1, 4), new Pos(0, 2, 4), new Pos(0, 3, 4));

	private static String check(Facing facing, Map<String, List<Pos>> blocks, Map<String, MovingPart> motions, Set<Pos> solid) {
		return MotionCheck.report(blocks, motions, facing, asked -> asked.stream().filter(solid::contains).collect(Collectors.toSet()));
	}

	private static MovingPart wing(Motion motion) {
		return new MovingPart("wing", HINGE, List.of(motion), "");
	}

	/** The same answer in every facing: it's all in Claude's local positions. */
	@Test
	void checkMotionSaysWhereAWingGoes() {
		for (Facing facing : Facing.values()) {
			String report = check(facing, Map.of("wing", WING), Map.of("wing", wing(new Motion.Swing("forward", 0, -90, 40, 0))), POST);
			assertTrue(report.contains("wing (3 blocks):"), report);
			assertTrue(report.contains("as built [1, 3, 4] to [3, 3, 4]"), facing + ": " + report);
			assertTrue(report.contains("at 0 ticks [1, 3, 4] to [3, 3, 4]"), facing + ": " + report);
			assertTrue(report.contains("at 20 ticks [1, 4, 4] to [1, 6, 4]"), facing + ": " + report);
			assertTrue(report.contains("passes through no solid block"), facing + ": " + report);
		}
	}

	@Test
	void aWingThroughTheBodyIsSaidSo() {
		String report = check(Facing.EAST, Map.of("wing", WING), Map.of("wing", wing(new Motion.Swing("forward", 0, 90, 40, 0))), POST);
		assertTrue(report.contains("at 20 ticks [0, 1, 4] to [0, 3, 4]"), report);
		assertTrue(report.contains("passes through 3 solid blocks: [0, 1, 4], [0, 2, 4], [0, 3, 4]"), report);
	}

	/** About up, a positive spin carries a part on the right round to the front, the left and the back. */
	@Test
	void aSpinIsCheckedAtQuarterTurns() {
		MovingPart arm = new MovingPart("arm", new Vec3(0, 0, 0), List.of(new Motion.Spin("up", 40, 0)), "");
		String report = check(Facing.NORTH, Map.of("arm", List.of(new Pos(2, 0, 0))), Map.of("arm", arm), Set.of());
		assertTrue(report.contains("at 0 ticks [2, 0, 0] to [2, 0, 0]"), report);
		assertTrue(report.contains("at 10 ticks [0, 0, 2] to [0, 0, 2]"), report);
		assertTrue(report.contains("at 20 ticks [-2, 0, 0] to [-2, 0, 0]"), report);
		assertTrue(report.contains("at 30 ticks [0, 0, -2] to [0, 0, -2]"), report);
	}

	/** A part riding another is checked over the longer cycle, its parent's motion included. */
	@Test
	void aRiderIsCheckedWithItsParent() {
		MovingPart arm = new MovingPart("arm", new Vec3(0, 0, 0), List.of(new Motion.Spin("up", 80, 0)), "");
		MovingPart flag = new MovingPart("flag", new Vec3(2, 0, 0), List.of(new Motion.Bob("up", 1, 20, 0)), "arm");
		Map<String, List<Pos>> blocks = new LinkedHashMap<>();
		blocks.put("arm", List.of(new Pos(1, 0, 0)));
		blocks.put("flag", List.of(new Pos(2, 0, 0)));
		String report = check(Facing.SOUTH, blocks, Map.of("arm", arm, "flag", flag), Set.of());
		assertTrue(report.contains("flag (1 block, riding arm):"), report);
		assertTrue(report.contains("at 40 ticks [-2, 0, 0] to [-2, 0, 0]"), report);
	}

	@Test
	void noPartsIsSaidSo() {
		assertTrue(check(Facing.SOUTH, Map.of(), Map.of(), Set.of()).contains("no moving parts"));
	}

	/** A part with no motion yet stands still, and the answer says how to give it one. */
	@Test
	void aPartWithNoMotionStandsStill() {
		String report = check(Facing.SOUTH, Map.of("wing", WING), Map.of(), POST);
		assertTrue(report.contains("wing (3 blocks): stands still"), report);
		assertTrue(report.contains("animate"), report);
	}
}
