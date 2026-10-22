package io.palmisano.aibuilder.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;

class ToolCatalogTest {
	@Test
	void everyCatalogToolHasAHandlerAndBack() {
		assertEquals(ToolExecutor.handledTools(), ToolCatalog.load().names());
	}

	/** Claude Code refuses a tool list that breaks these (spec §8), so a bad entry would fail every build. */
	@Test
	void everyToolFitsClaudeCodesLimits() {
		for (ToolDef tool : ToolCatalog.load().tools()) {
			assertTrue(tool.description().length() < 4096, tool.name() + "'s description is too long");
			assertEquals("object", tool.inputSchema().get("type").getAsString(), tool.name());
			for (String name : propertyNames(tool.inputSchema())) {
				assertTrue(name.matches("[A-Za-z0-9_.-]{1,64}"), tool.name() + " has a property called \"" + name + "\"");
			}
		}
	}

	/** Every property name in a schema, nested ones included. */
	private static List<String> propertyNames(JsonElement schema) {
		List<String> names = new ArrayList<>();
		if (!schema.isJsonObject()) {
			return names;
		}
		var object = schema.getAsJsonObject();
		if (object.has("properties")) {
			for (Map.Entry<String, JsonElement> property : object.getAsJsonObject("properties").entrySet()) {
				names.add(property.getKey());
				names.addAll(propertyNames(property.getValue()));
			}
		}
		if (object.has("items")) {
			names.addAll(propertyNames(object.get("items")));
		}
		return names;
	}
}
