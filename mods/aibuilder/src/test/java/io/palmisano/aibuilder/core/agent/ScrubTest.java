package io.palmisano.aibuilder.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScrubTest {
	private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	@Test
	void scrubsBearerTokensAndKeys() {
		Scrub scrub = new Scrub();
		scrub.add(TOKEN);
		assertEquals("Authorization: Bearer [redacted]", scrub.apply("Authorization: Bearer " + TOKEN));
		assertEquals("the token [redacted] on its own", scrub.apply("the token " + TOKEN + " on its own"));
		assertEquals("{\"headers\":{\"Authorization\":\"bearer [redacted]\"}}",
				scrub.apply("{\"headers\":{\"Authorization\":\"bearer eyJhbGciOiJIUzI1NiJ9.e30.ZRrHA1JJJW8opsbCGfG_HACGpVUMN_a9IV7pAx_Zmeo\"}}"));
		assertEquals("ANTHROPIC_API_KEY=[redacted], an admin key [redacted].",
				scrub.apply("ANTHROPIC_API_KEY=sk-ant-api03-AbC_dEf-1234567890xyz, an admin key sk-ant-admin01-zzzzzzzzzzzzzzzz."));
		assertEquals("a login [redacted]", scrub.apply("a login sk-ant-oat01-Q2xhdWRlQ29kZU9BdXRo"));
	}

	@Test
	void ordinaryTextStays() {
		Scrub scrub = new Scrub();
		scrub.add(TOKEN);
		String text = "A standard bearer stands by the gate; sk-ant is not a key, and neither is ask-anthony.";
		assertEquals(text, scrub.apply(text));
	}

	@Test
	void shortSecretsAreIgnored() {
		Scrub scrub = new Scrub();
		scrub.add("");
		scrub.add("oak");
		assertEquals("oak planks", scrub.apply("oak planks"));
	}
}
