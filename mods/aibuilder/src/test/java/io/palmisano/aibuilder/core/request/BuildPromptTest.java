package io.palmisano.aibuilder.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.palmisano.aibuilder.core.request.BuildPrompt.Problem;
import org.junit.jupiter.api.Test;

class BuildPromptTest {
	@Test
	void normalizeStripsOuterWhitespaceOnly() {
		assertEquals("castle\n with a moat", BuildPrompt.normalize("  castle\n with a moat \n"));
	}

	@Test
	void normalizeTreatsNullAsEmpty() {
		assertEquals("", BuildPrompt.normalize(null));
	}

	@Test
	void blankIsEmpty() {
		assertEquals(Problem.EMPTY, BuildPrompt.check(BuildPrompt.normalize(" \n\t ")));
	}

	@Test
	void exactlyTheLimitIsFine() {
		assertEquals(Problem.NONE, BuildPrompt.check("a".repeat(2000)));
	}

	@Test
	void overTheLimitIsTooLong() {
		assertEquals(Problem.TOO_LONG, BuildPrompt.check("a".repeat(2001)));
	}

	@Test
	void limitCountsCharactersNotUtf16Units() {
		assertEquals(Problem.NONE, BuildPrompt.check("🐟".repeat(2000)));
		assertEquals(Problem.TOO_LONG, BuildPrompt.check("🐟".repeat(2001)));
	}
}
