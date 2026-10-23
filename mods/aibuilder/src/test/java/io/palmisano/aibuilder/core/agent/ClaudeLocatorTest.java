package io.palmisano.aibuilder.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Finding the claude binary: the game launcher doesn't get the shell's PATH (spec §12.2). */
class ClaudeLocatorTest {
	private static final Path HOME = Path.of("/Users/f");
	private static final Map<String, String> ENV = Map.of("PATH", "/opt/tools/bin:/usr/bin");

	private static Optional<Path> find(String configured, Map<String, String> env, String os, Set<Path> present) {
		return ClaudeLocator.find(configured, env, os, HOME, present::contains);
	}

	@Test
	void configuredWins() {
		Path mine = Path.of("/opt/claude/claude");
		assertEquals(Optional.of(mine), find(mine.toString(), ENV, "Mac OS X", Set.of(mine, HOME.resolve(".local/bin/claude"))));
	}

	@Test
	void aConfiguredPathThatIsntThereFallsThrough() {
		Path local = HOME.resolve(".local/bin/claude");
		assertEquals(Optional.of(local), find("/nowhere/claude", ENV, "Mac OS X", Set.of(local)));
		assertEquals(Optional.of(local), find("  ", ENV, "Mac OS X", Set.of(local)));
	}

	/** A claude_path that isn't there (or can't run) is passed over, with a line saying so; none or one that runs, nothing. */
	@Test
	void aMissingClaudePathIsSaidSo() {
		Path mine = Path.of("/opt/claude/claude");
		assertTrue(ClaudeLocator.configuredProblem("/nowhere/claude", Set.of(mine)::contains).orElseThrow().contains("/nowhere/claude"));
		assertEquals(Optional.empty(), ClaudeLocator.configuredProblem(mine.toString(), Set.of(mine)::contains));
		assertEquals(Optional.empty(), ClaudeLocator.configuredProblem("  ", Set.of(mine)::contains));
	}

	@Test
	void knownPlacesBeforePath() {
		Path local = HOME.resolve(".local/bin/claude");
		Path brew = Path.of("/opt/homebrew/bin/claude");
		Path usrLocal = Path.of("/usr/local/bin/claude");
		Path onPath = Path.of("/usr/bin/claude");
		assertEquals(Optional.of(local), find(null, ENV, "Mac OS X", Set.of(local, brew, usrLocal, onPath)));
		assertEquals(Optional.of(brew), find(null, ENV, "Mac OS X", Set.of(brew, usrLocal, onPath)));
		assertEquals(Optional.of(usrLocal), find(null, ENV, "Mac OS X", Set.of(usrLocal, onPath)));
	}

	@Test
	void pathIsSearched() {
		Path tool = Path.of("/usr/bin/claude");
		assertEquals(Optional.of(tool), find(null, ENV, "Linux", Set.of(tool)));
	}

	@Test
	void windowsNames() {
		Map<String, String> env = Map.of("Path", "C:\\tools;C:\\bin", "USERPROFILE", "C:\\Users\\f");
		Path profile = Path.of("C:\\Users\\f", ".local", "bin", "claude.exe");
		Path cmd = Path.of("C:\\bin", "claude.cmd");
		assertEquals(Optional.of(profile), find(null, env, "Windows 11", Set.of(profile, cmd)));
		assertEquals(Optional.of(cmd), find(null, env, "Windows 11", Set.of(cmd)));
	}

	@Test
	void nothingFound() {
		assertEquals(Optional.empty(), find(null, ENV, "Mac OS X", Set.of()));
		assertEquals(Optional.empty(), find(null, Map.of(), "Linux", Set.of()));
	}
}
