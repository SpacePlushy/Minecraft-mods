package io.palmisano.aibuilder.core.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** What the system prompt tells Claude about redstone matches the game (M8 review). */
class BuilderPromptTest {
	private static String prompt() throws IOException {
		try (InputStream in = BuilderPromptTest.class.getResourceAsStream("/aibuilder/prompts/builder_system.md")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** test_redstone waits in game ticks; a repeater's delay of 1 to 4 is in redstone ticks, 2 game ticks each. */
	@Test
	void repeaterTimesAreInGameTicks() throws IOException {
		String prompt = prompt();
		assertTrue(prompt.contains("a repeater adds 2 to 8"), "the repeater's delay in game ticks");
		assertFalse(prompt.contains("a repeater adds 1 to 4"), "half the real delay");
	}

	/** In 26.3 a repeater or comparator takes its input from the side its facing names (DiodeBlock.getInputSignal). */
	@Test
	void aDiodesFacingPointsAtItsInput() throws IOException {
		assertTrue(prompt().contains("facing points back at its input"), "which way a repeater faces");
	}

	/** The calculator is put together from gadgets and wire (the M9 plan): the prompt says how, and that it needs the Epic size. */
	@Test
	void theCalculatorIsBuiltFromGadgets() throws IOException {
		String prompt = prompt();
		assertTrue(prompt.contains("# Gadgets"), "a gadgets section");
		assertTrue(prompt.contains("needs the Epic size"), "the calculator's size");
		assertTrue(prompt.contains("level Y+7"), "the second row of wires over the first");
		assertTrue(prompt.contains("{\"show\": true}"), "how to read the digits");
	}
}
