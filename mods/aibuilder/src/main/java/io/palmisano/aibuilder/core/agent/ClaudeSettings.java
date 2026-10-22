package io.palmisano.aibuilder.core.agent;

import io.palmisano.aibuilder.core.Tunables;

/** The in-game Claude's model and effort (spec §2: Opus 5.5 at high, configurable in M6), and the turn backstop. */
public record ClaudeSettings(String model, String effort, int maxTurns) {
	public static final ClaudeSettings DEFAULT = new ClaudeSettings("claude-opus-5-5", "high", Tunables.MAX_TURNS);
}
