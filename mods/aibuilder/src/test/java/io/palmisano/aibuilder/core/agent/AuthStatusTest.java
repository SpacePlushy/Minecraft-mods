package io.palmisano.aibuilder.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@code claude auth status --json}, as the M4 spike saw it logged in and logged out. */
class AuthStatusTest {
	private static final String LOGGED_IN = """
			{
			  "loggedIn": true,
			  "authMethod": "claude.ai",
			  "apiProvider": "firstParty",
			  "analyticsDisabled": false,
			  "subscriptionType": "max"
			}
			""";
	private static final String LOGGED_OUT = """
			{
			  "loggedIn": false,
			  "authMethod": "none",
			  "apiProvider": "firstParty"
			}
			""";

	@Test
	void theSpikesLoggedInAnswerIsOk() {
		AuthStatus status = AuthStatus.parse(0, LOGGED_IN);
		assertTrue(status.ok());
		assertTrue(status.loggedIn());
		assertEquals("claude.ai", status.method());
	}

	@Test
	void loggedOutIsNot() {
		AuthStatus status = AuthStatus.parse(1, LOGGED_OUT);
		assertFalse(status.ok());
		assertFalse(status.loggedIn());
	}

	@Test
	void anOauthTokenIsOk() {
		assertTrue(AuthStatus.parse(0, "{\"loggedIn\": true, \"authMethod\": \"oauth_token\"}").ok());
	}

	@Test
	void anApiKeyLoginIsLoggedInButNotOk() {
		AuthStatus status = AuthStatus.parse(0, "{\"loggedIn\": true, \"authMethod\": \"api_key\"}");
		assertTrue(status.loggedIn());
		assertFalse(status.ok());
		assertEquals("api_key", status.method());
	}

	@Test
	void aFailedRunIsNotOkWhateverItPrints() {
		assertFalse(AuthStatus.parse(1, LOGGED_IN).ok());
	}

	@Test
	void garbageIsNot() {
		AuthStatus status = AuthStatus.parse(0, "Loading…");
		assertFalse(status.ok());
		assertFalse(status.loggedIn());
		assertEquals("", status.method());
		assertFalse(AuthStatus.parse(0, "").ok());
		assertFalse(AuthStatus.parse(0, "{\"loggedIn\": \"yes\"}").ok());
	}
}
