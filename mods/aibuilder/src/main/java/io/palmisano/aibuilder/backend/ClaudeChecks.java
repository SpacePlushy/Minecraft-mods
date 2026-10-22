package io.palmisano.aibuilder.backend;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import io.palmisano.aibuilder.core.agent.AuthStatus;
import io.palmisano.aibuilder.core.agent.ClaudeLocator;
import io.palmisano.aibuilder.core.request.Messages;

/**
 * Before a build launches (spec §12.2): find Claude Code, then ask it whether it's logged in with a subscription, in
 * the environment the build itself will run with. A pass is remembered for five minutes (it runs a process, and each
 * build would wait for it); a failure never is, so a fresh login works on the next try. Blocking: never call it on
 * the server thread.
 */
public final class ClaudeChecks {
	private static final long REMEMBER_NANOS = TimeUnit.MINUTES.toNanos(5);
	private static final int TIMEOUT_SECONDS = 15;

	private record Pass(List<String> command, Map<String, String> environment, long at) {
	}

	private static volatile Pass last;

	private ClaudeChecks() {
	}

	/** The command that runs Claude Code: {@code configured}, the installers' places, then {@code PATH}. */
	public static Optional<List<String>> command(String configured, Map<String, String> env, Path home, Predicate<Path> executable) {
		return ClaudeLocator.find(configured, env, System.getProperty("os.name", ""), home, executable).map(path -> List.of(path.toString()));
	}

	/** {@code claude auth status --json}: empty when the build may go, else the line for the player. */
	public static Optional<String> login(List<String> command, Map<String, String> environment) {
		Pass pass = last;
		if (pass != null && pass.command().equals(command) && pass.environment().equals(environment) && System.nanoTime() - pass.at() < REMEMBER_NANOS) {
			return Optional.empty();
		}
		List<String> args = new ArrayList<>(command);
		args.addAll(List.of("auth", "status", "--json"));
		ProcessBuilder builder = new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD);
		builder.environment().clear();
		builder.environment().putAll(environment);
		Process process;
		try {
			process = builder.start();
			process.getOutputStream().close();
		} catch (IOException e) {
			return Optional.of(Messages.notInstalled());
		}
		String out;
		try (InputStream in = process.getInputStream()) {
			CompletableFuture<byte[]> read = CompletableFuture.supplyAsync(() -> {
				try {
					return in.readNBytes(64 * 1024);
				} catch (IOException e) {
					return new byte[0];
				}
			});
			if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return Optional.of(Messages.claudeNotAnswering());
			}
			out = new String(read.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
		} catch (IOException | ExecutionException | TimeoutException e) {
			process.destroyForcibly();
			return Optional.of(Messages.claudeNotAnswering());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			return Optional.of(Messages.claudeNotAnswering());
		}
		AuthStatus status = AuthStatus.parse(process.exitValue(), out);
		if (status.ok()) {
			last = new Pass(List.copyOf(command), Map.copyOf(environment), System.nanoTime());
			return Optional.empty();
		}
		return Optional.of(status.loggedIn() ? Messages.notSubscription(status.method()) : Messages.notLoggedIn());
	}
}
