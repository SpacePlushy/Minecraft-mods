package io.palmisano.aibuilder.core.survey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.survey.BuildProblems.Kind;
import io.palmisano.aibuilder.core.survey.BuildProblems.Problem;
import org.junit.jupiter.api.Test;

/** check_build's answer (spec §8): problems grouped by kind, local positions, at most so many listed. */
class BuildProblemsTest {
	@Test
	void noProblems() {
		assertEquals("No problems found.", BuildProblems.encode(List.of(), 60));
	}

	@Test
	void problemsAreGroupedAndCapped() {
		List<Problem> problems = new ArrayList<>();
		for (int i = 9; i >= 0; i--) {
			problems.add(new Problem(Kind.DARK, new Pos(i, 1, 2), ""));
		}
		problems.add(new Problem(Kind.SPILL, new Pos(5, 2, 5), "minecraft:water"));
		problems.add(new Problem(Kind.UNSUPPORTED, new Pos(0, 3, 4), "minecraft:torch"));
		String text = BuildProblems.encode(problems, 6);
		assertTrue(text.startsWith("12 problems (local positions):"), text);
		// One line per kind, in a fixed order, the 6 listed shared out between them, the rest counted.
		assertTrue(text.indexOf("unsupported (1)") < text.indexOf("dark (10)") && text.indexOf("dark (10)") < text.indexOf("spill (1)"), text);
		assertTrue(text.contains("(0, 3, 4) torch"), text);
		assertTrue(text.contains("(5, 2, 5) water"), text);
		assertTrue(text.contains("(0, 1, 2); (1, 1, 2); (2, 1, 2); (3, 1, 2) … and 6 more"), text);
		assertFalse(text.contains("(4, 1, 2)"), text);
	}

	@Test
	void oneProblemIsSingular() {
		String text = BuildProblems.encode(List.of(new Problem(Kind.DOOR, new Pos(0, 1, 0), "")), 60);
		assertTrue(text.startsWith("1 problem (local positions):"), text);
		assertTrue(text.contains("door (1)"), text);
	}
}
