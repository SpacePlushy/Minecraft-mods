package io.palmisano.aibuilder.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.palmisano.aibuilder.core.agent.ClaudeLaunch;
import io.palmisano.aibuilder.core.request.Messages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Before a build launches: is Claude Code there, and logged in with a subscription? (spec §12.2), against the fake. */
class ClaudeChecksTest {
	private static final List<String> FAKE = List.of(ProcessHandle.current().info().command().orElse("java"),
			Path.of("src/test/resources/fake-claude/FakeClaude.java").toAbsolutePath().toString());

	private static Map<String, String> environment(Map<String, String> extra) {
		Map<String, String> env = new HashMap<>(System.getenv());
		env.putAll(extra);
		return ClaudeLaunch.environment(env);
	}

	@Test
	void theFakesLoginPasses() {
		assertEquals(Optional.empty(), ClaudeChecks.login(FAKE, environment(Map.of("FAKE_CLAUDE_RUN", "pass"))));
	}

	@Test
	void loggedOutSaysToLogIn() {
		assertEquals(Optional.of(Messages.notLoggedIn()), ClaudeChecks.login(FAKE, environment(Map.of("FAKE_CLAUDE_LOGGED_OUT", "1"))));
	}

	/** A check that passed is good for a while: it runs a process, and every build would wait for it. */
	@Test
	void aPassIsRemembered() {
		Map<String, String> env = environment(Map.of("FAKE_CLAUDE_RUN", "remembered"));
		assertEquals(Optional.empty(), ClaudeChecks.login(FAKE, env));
		long began = System.nanoTime();
		assertEquals(Optional.empty(), ClaudeChecks.login(FAKE, env));
		assertTrue(System.nanoTime() - began < 200_000_000L, "the second check ran the process again");
	}

	@Test
	void aCommandThatCantRunIsNotInstalled() {
		assertEquals(Optional.of(Messages.notInstalled()), ClaudeChecks.login(List.of("/nowhere/at/all/claude"), environment(Map.of())));
	}

	@Test
	void theCommandIsWhereTheInstallerPutsIt(@TempDir Path home) throws Exception {
		Map<String, String> env = Map.of("PATH", home.resolve("bin").toString());
		// Only this temporary home counts, whatever this machine has installed.
		assertEquals(Optional.empty(), ClaudeChecks.command(null, env, home, path -> path.startsWith(home) && Files.isExecutable(path)));
		Path claude = home.resolve(".local/bin/claude");
		Files.createDirectories(claude.getParent());
		Files.writeString(claude, "#!/bin/sh\n");
		claude.toFile().setExecutable(true);
		assertEquals(Optional.of(List.of(claude.toString())),
				ClaudeChecks.command(null, env, home, path -> path.startsWith(home) && Files.isExecutable(path)));
	}
}
