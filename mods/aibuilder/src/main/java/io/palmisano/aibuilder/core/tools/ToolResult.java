package io.palmisano.aibuilder.core.tools;

/** What a tool call tells Claude. */
public record ToolResult(String text, boolean isError) {
	static ToolResult ok(String text) {
		return new ToolResult(text, false);
	}

	static ToolResult error(String text) {
		return new ToolResult(text, true);
	}
}
