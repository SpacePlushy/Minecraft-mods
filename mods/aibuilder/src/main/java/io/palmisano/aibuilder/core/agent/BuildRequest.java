package io.palmisano.aibuilder.core.agent;

import java.util.UUID;

import io.palmisano.aibuilder.core.request.SizeTier;

/**
 * What a backend builds: the prompt as Claude reads it, the size, and the Claude Code session it runs in;
 * {@code resume}: a refinement, going on in that session (spec §5.4).
 */
public record BuildRequest(String prompt, SizeTier size, UUID session, boolean resume) {
	/** A new build, in a session of its own. */
	public BuildRequest(String prompt, SizeTier size) {
		this(prompt, size, UUID.randomUUID(), false);
	}
}
