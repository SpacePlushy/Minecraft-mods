package io.palmisano.aibuilder.core.agent;

import io.palmisano.aibuilder.core.tools.ToolExecutor;

/** Something that drives a build through the tools: a script, or Claude Code. */
public interface AiBackend {
	BuildHandle start(BuildRequest request, ToolExecutor tools, BuildEvents events);
}
