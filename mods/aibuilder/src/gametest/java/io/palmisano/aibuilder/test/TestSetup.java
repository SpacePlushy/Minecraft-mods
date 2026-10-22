package io.palmisano.aibuilder.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.AiBuilderMod;
import io.palmisano.aibuilder.TestHooks;
import net.fabricmc.api.ModInitializer;

/**
 * Every gametest builds with the fake Claude Code (src/test/resources/fake-claude), never the real one on the owner's
 * login (spec §17.5), unless {@code AIBUILDER_LIVE=1} asks for a live check. The fake goes in only on a gametest run,
 * known by Fabric's gametest properties or by the run folder (build/run/…; {@code runClient} plays in run/), so this
 * test mod could never swap the backend in normal play.
 */
public class TestSetup implements ModInitializer {
	static final Map<String, String> DEFAULT_SCENARIO = Map.of("FAKE_CLAUDE_SCENARIO", "echo");

	@Override
	public void onInitialize() {
		if (live()) {
			AiBuilderMod.LOG.warn("AIBUILDER_LIVE=1: these gametests build with the real Claude Code, on the owner's login");
			return;
		}
		Path folder = Path.of("").toAbsolutePath();
		String where = folder.toString().replace('\\', '/');
		boolean gametest = System.getProperty("fabric-api.gametest") != null || System.getProperty("fabric.client.gametest") != null
				|| where.contains("/build/run/");
		if (!gametest) {
			AiBuilderMod.LOG.warn("AI Builder's test mod is loaded outside a gametest run ({}); builds use the real Claude Code", folder);
			return;
		}
		Path fake = fake(folder);
		TestHooks.claudeCommand = List.of(ProcessHandle.current().info().command().orElse("java"), fake.toString());
		TestHooks.claudeEnvironment = DEFAULT_SCENARIO;
		AiBuilderMod.LOG.info("Gametests build with the fake Claude Code ({})", fake);
	}

	static boolean live() {
		return "1".equals(System.getenv("AIBUILDER_LIVE"));
	}

	/** The fake's source, found from the run folder (build/run/<run>) up to the mod's folder. */
	private static Path fake(Path folder) {
		for (Path dir = folder; dir != null; dir = dir.getParent()) {
			Path candidate = dir.resolve("src/test/resources/fake-claude/FakeClaude.java");
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		throw new IllegalStateException("the fake Claude Code isn't anywhere above " + folder);
	}
}
