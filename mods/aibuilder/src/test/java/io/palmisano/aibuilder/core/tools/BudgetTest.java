package io.palmisano.aibuilder.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The mod's own caps (spec §12.2): tool calls, output tokens and time, and what Claude reads as they run low. */
class BudgetTest {
	private long now;

	private Budget budget(int calls, long tokens, long minutes) {
		return new Budget(calls, tokens, minutes * 60_000, () -> now);
	}

	@Test
	void callsRunOut() {
		Budget b = budget(5, 500_000, 45);
		for (int i = 0; i < 5; i++) {
			b.count();
		}
		assertFalse(b.exhausted(), "the fifth call is still inside the budget");
		b.count();
		assertTrue(b.exhausted());
		assertEquals("the 5 tool-call limit", b.limitReached());
	}

	@Test
	void tokensRunOut() {
		Budget b = budget(150, 1000, 45);
		b.addOutputTokens(999);
		assertFalse(b.exhausted());
		assertEquals("", b.limitReached());
		b.addOutputTokens(1);
		assertTrue(b.exhausted());
		assertEquals(1000, b.outputTokens());
		assertEquals("the 1,000-token limit", b.limitReached());
	}

	@Test
	void timeRunsOut() {
		Budget b = budget(150, 500_000, 45);
		now = 45 * 60_000 - 1;
		assertFalse(b.exhausted());
		now = 45 * 60_000;
		assertTrue(b.exhausted());
		assertEquals("the 45-minute limit", b.limitReached());
	}

	@Test
	void footerNamesWhatsLow() {
		Budget b = budget(100, 1000, 60);
		for (int i = 0; i < 80; i++) {
			b.count();
		}
		assertEquals("Budget: 20 tool calls left.", b.footer());
		b.addOutputTokens(800);
		assertEquals("Budget: 20 tool calls left, about 200 output tokens left.", b.footer());
		now = 50 * 60_000;
		assertEquals("Budget: 20 tool calls left, about 200 output tokens left, 10 minutes left.", b.footer());
		now = 55 * 60_000;
		assertEquals("Budget nearly spent: finish now.", b.footer());
	}

	@Test
	void oneArgumentBudgetHasNoTokenOrTimeLimit() {
		Budget b = new Budget(150);
		b.addOutputTokens(Long.MAX_VALUE / 2);
		assertFalse(b.exhausted());
		assertEquals("", b.limitReached());
		assertEquals("", b.footer());
	}
}
