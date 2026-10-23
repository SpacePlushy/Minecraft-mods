package io.palmisano.aibuilder.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.Box;
import org.junit.jupiter.api.Test;

/** Refine or build new (spec §5.4): the last build, when the player is near it in the same dimension. */
class RefinementTest {
	/** A castle facing south from (100, 64, 0): it covers x 90..110 (local −10..10), z 3..43 (local 3..43). */
	private static final BuildSession CASTLE = new BuildSession("minecraft:overworld", "Stonehold Castle", new Pos(100, 64, 0), Facing.SOUTH,
			SizeTier.MEDIUM, Box.of(-10, 0, 3, 10, 30, 43), "7b0f3c1e-1111-4222-8333-444455556666", List.of("a castle"), 1, false, "");

	@Test
	void withinFortyEightBlocksRefines() {
		assertEquals(Optional.of(CASTLE), Refinement.decide(CASTLE, false, new Pos(100, 64, 20), "minecraft:overworld"), "inside it");
		assertEquals(Optional.of(CASTLE), Refinement.decide(CASTLE, false, new Pos(100, 200, -40), "minecraft:overworld"),
				"43 in front of it, high up: sideways counts");
		assertEquals(Optional.of(CASTLE), Refinement.decide(CASTLE, false, new Pos(100 + 10 + 48, 64, 20), "minecraft:overworld"),
				"48 off its side");
	}

	@Test
	void fartherOrElsewhereIsNew() {
		assertEquals(Optional.empty(), Refinement.decide(CASTLE, false, new Pos(100 + 10 + 49, 64, 20), "minecraft:overworld"), "49 off");
		assertEquals(Optional.empty(), Refinement.decide(CASTLE, false, new Pos(100, 64, 20), "minecraft:the_nether"), "another dimension");
		assertEquals(Optional.empty(), Refinement.decide(null, false, new Pos(100, 64, 20), "minecraft:overworld"), "no build yet");
	}

	/** The moving build a session's parts are in goes with it through refinements and undos (spec v2 §4.4). */
	@Test
	void aSessionKeepsItsMovingBuild() {
		String moving = "5c1f0d7e-aaaa-4bbb-8ccc-ddddeeeeffff";
		BuildSession dragon = CASTLE.withMovingBuild(moving);
		assertEquals("", CASTLE.movingBuild());
		BuildSession faster = dragon.refined("flap faster", Box.of(0, 0, 3, 2, 2, 5), "7b0f3c1e-1111-4222-8333-444455556666", "");
		assertEquals(moving, faster.movingBuild());
		assertEquals(moving, faster.undone().orElseThrow().movingBuild());
	}

	@Test
	void newAlwaysBuildsNew() {
		assertEquals(Optional.empty(), Refinement.decide(CASTLE, true, new Pos(100, 64, 20), "minecraft:overworld"));
	}

	/** M6 Review Focus: with its first build undone the blocks are gone, so the session ends; a refinement undone leaves it. */
	@Test
	void undoingTheLastBuildEndsItsSession() {
		assertEquals(Optional.empty(), CASTLE.undone(), "the castle itself undone");
		BuildSession moated = CASTLE.refined("add a moat", Box.of(-14, -2, 0, 14, 0, 47), "7b0f3c1e-1111-4222-8333-444455556666", "");
		assertEquals(2, moated.builds());
		assertEquals(Box.of(-14, -2, 0, 14, 30, 47), moated.changed(), "the moat's changes join the box");
		assertEquals(List.of("a castle", "add a moat"), moated.prompts());
		BuildSession back = moated.undone().orElseThrow();
		assertEquals(1, back.builds());
		assertTrue(back.lastUndone());
		assertFalse(back.refined("add towers", Box.of(0, 0, 0, 0, 0, 0), back.claudeSession(), "").lastUndone());
	}

	/** What Claude reads for a refinement: what to change, of what, and what it should know first. */
	@Test
	void aRefinementTellsClaudeWhatChanged() {
		String text = Refinement.prompt(CASTLE, "add a moat");
		assertTrue(text.contains("Stonehold Castle") && text.contains("add a moat") && text.contains("get_context"), text);
		assertFalse(text.contains("undid"), text);
		BuildSession undone = CASTLE.refined("add a moat", Box.of(0, 0, 0, 0, 0, 0), CASTLE.claudeSession(), "").undone().orElseThrow();
		assertTrue(Refinement.prompt(undone, "add towers").contains("undid"), Refinement.prompt(undone, "add towers"));
	}

	/** A later brief's title names the build from then on; an empty one keeps the old. */
	@Test
	void aNewTitleTakesOver() {
		assertEquals("Stonehold with a moat", CASTLE.refined("add a moat", CASTLE.changed(), CASTLE.claudeSession(), "Stonehold with a moat").title());
		assertEquals("Stonehold Castle", CASTLE.refined("add a moat", CASTLE.changed(), CASTLE.claudeSession(), "").title());
	}
}
