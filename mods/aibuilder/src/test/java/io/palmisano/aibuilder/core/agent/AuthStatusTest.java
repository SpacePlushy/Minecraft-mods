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
	void onlyJsonIsAnAnswer() {
		assertTrue(AuthStatus.parse(1, LOGGED_OUT).answered());
		assertFalse(AuthStatus.parse(1, "").answered());
		assertFalse(AuthStatus.parse(0, "Usage: claude [options] [command] [prompt]").answered());
		assertFalse(AuthStatus.parse(0, "{\"authMethod\": \"claude.ai\"}").answered());
	}

	/** What a broken install printed, for the player: its error line, not Node's stack or its version banner. */
	@Test
	void whyPicksTheErrorLine() {
		String node = """
				node:internal/modules/cjs/loader:1228
				  throw err;
				  ^

				Error: Cannot find module '/usr/local/lib/node_modules/@anthropic-ai/claude-code/cli.js'
				    at Module._resolveFilename (node:internal/modules/cjs/loader:1225:15)
				    at node:internal/main/run_main_module:28:49 {
				  code: 'MODULE_NOT_FOUND',
				  requireStack: []
				}

				Node.js v22.1.0
				""";
		assertEquals("Error: Cannot find module '/usr/local/lib/node_modules/@anthropic-ai/claude-code/cli.js'", AuthStatus.why(node, 1));
		assertEquals("Segmentation fault", AuthStatus.why("\nSegmentation fault\n", 139));
		assertEquals("exit code 3", AuthStatus.why("", 3));
		assertEquals("no answer to claude auth status", AuthStatus.why("  \n", 0));
		assertEquals(200, AuthStatus.why("Error: " + "x".repeat(500), 1).length());
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
