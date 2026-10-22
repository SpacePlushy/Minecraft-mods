package io.palmisano.aibuilder.core.agent;

import com.google.gson.JsonObject;
import io.palmisano.aibuilder.core.tools.ToolResult;

/** One build as the MCP server sees it: the tools its token reaches. */
public interface McpSession {
	/** Waits, at most {@code millis}, for the build's start check (spec §12.2); false if it failed or is still pending. */
	boolean awaitReady(long millis);

	/** One tool call; blocks as long as the tool does (a checkpoint waits for placement). */
	ToolResult call(String name, JsonObject arguments);
}
