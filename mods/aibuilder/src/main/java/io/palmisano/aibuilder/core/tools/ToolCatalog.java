package io.palmisano.aibuilder.core.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The tool definitions Claude sees, from {@code aibuilder/tools.json}: the one source for every backend. */
public final class ToolCatalog {
	private static final String RESOURCE = "/aibuilder/tools.json";

	private final List<ToolDef> tools;

	private ToolCatalog(List<ToolDef> tools) {
		this.tools = List.copyOf(tools);
	}

	public static ToolCatalog load() {
		try (InputStream in = ToolCatalog.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException(RESOURCE + " is missing from the jar");
			}
			List<ToolDef> tools = new ArrayList<>();
			for (JsonElement element : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray()) {
				JsonObject tool = element.getAsJsonObject();
				tools.add(new ToolDef(tool.get("name").getAsString(), tool.get("description").getAsString(),
						tool.getAsJsonObject("input_schema")));
			}
			return new ToolCatalog(tools);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public List<ToolDef> tools() {
		return tools;
	}

	public Set<String> names() {
		Set<String> names = new LinkedHashSet<>();
		tools.forEach(t -> names.add(t.name()));
		return names;
	}
}
