package io.palmisano.aibuilder.backend;

import io.palmisano.aibuilder.core.agent.McpSessions;

/** Where a build's Claude Code reaches the mod's tools: the MCP server's port, and the tokens it answers. */
public record McpEndpoint(int port, McpSessions sessions) {
}
