package io.palmisano.aibuilder;

import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.config.AiBuilderConfig;

/** Seams for the mod's own gametests; nothing in the mod sets them. */
public final class TestHooks {
	/** When set, builds launch this instead of the claude binary (the gametests' fake Claude Code). */
	public static volatile List<String> claudeCommand;
	/** Added to the child's environment (the fake's scenario). */
	public static volatile Map<String, String> claudeEnvironment = Map.of();
	/** When set, the settings BuildManager uses instead of the file's (a server with two players allowed to build). */
	public static volatile AiBuilderConfig config;

	private TestHooks() {
	}
}
