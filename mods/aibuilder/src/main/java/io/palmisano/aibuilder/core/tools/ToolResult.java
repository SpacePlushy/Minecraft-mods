package io.palmisano.aibuilder.core.tools;

import java.util.List;

/** What a tool call tells Claude; {@code rejections}, every entry a write turned away (the text lists the first few). */
public record ToolResult(String text, boolean isError, List<String> rejections) {
	public ToolResult(String text, boolean isError) {
		this(text, isError, List.of());
	}

	static ToolResult ok(String text) {
		return new ToolResult(text, false);
	}

	static ToolResult error(String text) {
		return new ToolResult(text, true);
	}
}
