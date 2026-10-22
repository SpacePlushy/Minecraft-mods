package io.palmisano.aibuilder.core.tools;

import com.google.gson.JsonObject;

public record ToolDef(String name, String description, JsonObject inputSchema) {
}
