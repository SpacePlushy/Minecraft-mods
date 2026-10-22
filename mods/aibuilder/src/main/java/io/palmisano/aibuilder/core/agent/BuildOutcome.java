package io.palmisano.aibuilder.core.agent;

public enum BuildOutcome {
	FINISHED,
	CANCELLED,
	FAILED,
	/** Stopped at one of the mod's caps (tool calls, tokens, minutes). */
	CAPPED,
	/** Stopped at the plan's usage limit, or where paid extra usage would have begun. */
	LIMITED
}
