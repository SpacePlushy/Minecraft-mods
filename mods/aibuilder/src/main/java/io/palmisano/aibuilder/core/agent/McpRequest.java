package io.palmisano.aibuilder.core.agent;

import java.util.Map;

/** One HTTP request to {@code /mcp}: the method, the headers (names lower case, first value each) and the body. */
public record McpRequest(String method, Map<String, String> headers, String body) {
}
