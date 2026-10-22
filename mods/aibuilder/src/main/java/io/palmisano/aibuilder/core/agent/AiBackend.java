package io.palmisano.aibuilder.core.agent;

import io.palmisano.aibuilder.core.tools.ToolExecutor;

/** Something that drives a build through the tools: a script now, Claude Code from M4. */
public interface AiBackend {
	BuildHandle start(BuildRequest request, ToolExecutor tools);
}
