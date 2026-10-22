package io.palmisano.aibuilder.core.agent;

import java.util.List;

/**
 * One line of Claude Code's stream ({@code claude -p --output-format stream-json}), as much of it as the mod uses.
 * The shapes come from the M4 spike's recordings of Claude Code 2.1.306.
 */
public sealed interface StreamEvent {
	/** {@code system/init}: what this Claude Code process started with, before its first model call. */
	record Init(String sessionId, String model, String version, List<String> tools, List<Server> servers, String apiKeySource,
			List<Plugin> plugins) implements StreamEvent {
	}

	/** One MCP server in {@link Init}: its name and whether it connected. */
	record Server(String name, String status) {
	}

	/** One plugin in {@link Init}; Claude Code's own have the path {@code builtin}. */
	record Plugin(String name, String path) {
	}

	/** A model message: the tools it calls, its text, and an API error such as {@code authentication_failed}. */
	record Assistant(String messageId, List<String> toolNames, String text, String error) implements StreamEvent {
	}

	/** One finished message's output tokens (its {@code message_delta}). */
	record OutputTokens(long tokens) implements StreamEvent {
	}

	/** {@code system/api_retry}: the API was busy or failed, and Claude Code is trying again. */
	record Retry(int attempt, String error) implements StreamEvent {
	}

	/**
	 * {@code rate_limit_event}: the plan's usage window, whether paid extra usage is being used, whether the account
	 * allows it at all ({@code overageStatus}: "rejected" when it's off), and how full the fullest window is (0..1).
	 * Claude Code sends one with the first response and again whenever the state changes (the M4 live build).
	 */
	record RateLimit(String status, long resetsAt, String type, boolean usingOverage, String overageStatus, double utilization)
			implements StreamEvent {
	}

	/** {@code result}: the run is over. */
	record Result(String subtype, boolean isError, int turns, String text, String terminalReason) implements StreamEvent {
	}

	/** Any other line, by its type (and subtype): {@code system/status}, {@code user}, {@code tool_progress}… */
	record Other(String type) implements StreamEvent {
	}
}
