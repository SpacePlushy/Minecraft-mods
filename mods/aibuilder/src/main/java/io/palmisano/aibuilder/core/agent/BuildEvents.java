package io.palmisano.aibuilder.core.agent;

/** What a backend tells the player while it works, besides its tool calls. Called from any thread. */
public interface BuildEvents {
	BuildEvents NONE = new BuildEvents() {
		@Override
		public void status(String note) {
		}

		@Override
		public void warn(String line) {
		}
	};

	/** The action bar's note before the brief ("Starting Claude…") or during a retry; "" clears it. */
	void status(String note);

	/** A line for chat, such as a usage warning. */
	void warn(String line);

	/** Claude Code started: its version and the model it builds with, for the build log. */
	default void started(String version, String model) {
	}

	/** One model turn's tokens, for the build log. */
	default void usage(long input, long output, long cacheRead, long cacheWrite) {
	}
}
