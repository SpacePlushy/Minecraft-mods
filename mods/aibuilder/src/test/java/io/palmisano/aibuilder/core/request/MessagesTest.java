package io.palmisano.aibuilder.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.palmisano.aibuilder.core.request.BuildPrompt.Problem;
import org.junit.jupiter.api.Test;

class MessagesTest {
	@Test
	void receivedEchoesPromptAndSize() {
		assertEquals("AI Builder got: castle (Small)", Messages.received("castle", SizeTier.SMALL));
	}

	@Test
	void receivedKeepsNonAsciiIntact() {
		assertEquals("AI Builder got: 日本庭園 with a koi pond 🐟 (Auto)", Messages.received("日本庭園 with a koi pond 🐟", SizeTier.AUTO));
	}

	@Test
	void tooLongSaysHowLong() {
		assertEquals("That prompt is too long: 2,345 characters, the limit is 2,000.",
				Messages.problem(Problem.TOO_LONG, "a".repeat(2345)));
	}
}
