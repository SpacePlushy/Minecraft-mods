package io.palmisano.aibuilder.core.agent;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Where the {@code claude} binary is (spec §12.2). The game launcher doesn't get the shell's {@code PATH}, so the
 * places the installers use come first.
 */
public final class ClaudeLocator {
	private ClaudeLocator() {
	}

	/** {@code configured} (blank: none), the installers' places, then {@code PATH}; the first that {@code executable} accepts. */
	public static Optional<Path> find(String configured, Map<String, String> env, String osName, Path home, Predicate<Path> executable) {
		boolean windows = osName.toLowerCase(Locale.ROOT).startsWith("windows");
		List<Path> candidates = new ArrayList<>();
		if (configured != null && !configured.isBlank()) {
			path(configured.strip()).ifPresent(candidates::add);
		}
		candidates.add(home.resolve(".local").resolve("bin").resolve("claude"));
		candidates.add(Path.of("/opt/homebrew/bin/claude"));
		candidates.add(Path.of("/usr/local/bin/claude"));
		if (windows) {
			String profile = variable(env, "USERPROFILE");
			if (!profile.isEmpty()) {
				path(profile).ifPresent(p -> candidates.add(p.resolve(".local").resolve("bin").resolve("claude.exe")));
			}
		}
		String separator = windows ? ";" : ":";
		for (String dir : variable(env, "PATH").split(separator)) {
			if (dir.isBlank()) {
				continue;
			}
			for (String name : windows ? List.of("claude.exe", "claude.cmd") : List.of("claude")) {
				path(dir).ifPresent(p -> candidates.add(p.resolve(name)));
			}
		}
		return candidates.stream().filter(executable).findFirst();
	}

	/** Why {@code configured} (the config's claude_path) won't do, for the log; empty when it's blank or runs. */
	public static Optional<String> configuredProblem(String configured, Predicate<Path> executable) {
		if (configured == null || configured.isBlank()) {
			return Optional.empty();
		}
		Optional<Path> path = path(configured.strip());
		if (path.isPresent() && executable.test(path.get())) {
			return Optional.empty();
		}
		return Optional.of("claude_path \"" + configured.strip() + "\" in config/aibuilder.json isn't a program that runs (it isn't there, or"
				+ " isn't executable); looking in the usual places instead");
	}

	/** Environment names are case-insensitive on Windows ({@code Path}). */
	private static String variable(Map<String, String> env, String name) {
		return env.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse("");
	}

	private static Optional<Path> path(String text) {
		try {
			return Optional.of(Path.of(text));
		} catch (InvalidPathException e) {
			return Optional.empty();
		}
	}
}
