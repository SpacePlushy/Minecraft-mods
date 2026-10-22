package io.palmisano.aibuilder.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** The start check (spec §12.2): the build tools only, our server only, the subscription, no add-ons. */
class InitCheckTest {
	private static final List<StreamEvent.Plugin> BUILT_IN = List.of(new StreamEvent.Plugin("cc-plugin-telemetry", "builtin"));
	private static final List<StreamEvent.Server> OURS = List.of(new StreamEvent.Server("aibuilder", "connected"));

	private static StreamEvent.Init init(List<String> tools, List<StreamEvent.Server> servers, String apiKeySource,
			List<StreamEvent.Plugin> plugins) {
		return new StreamEvent.Init("s", "claude-opus-5-5", "2.1.306", tools, servers, apiKeySource, plugins);
	}

	private static String problem(StreamEvent.Init init) {
		return InitCheck.problem(init).orElse("");
	}

	@Test
	void theSpikesRealStartPasses() {
		StreamEvent.Init real = StreamEventsTest.only(StreamEventsTest.read("success.jsonl"), StreamEvent.Init.class).getFirst();
		assertEquals(Optional.empty(), InitCheck.problem(real));
	}

	@Test
	void endConversationIsAllowed() {
		assertEquals(Optional.empty(), InitCheck.problem(init(List.of("mcp__aibuilder__fill", "EndConversation"), OURS, "none", BUILT_IN)));
	}

	@Test
	void anExtraToolFails() {
		String problem = problem(init(List.of("mcp__aibuilder__fill", "Bash"), OURS, "none", BUILT_IN));
		assertTrue(problem.contains("Bash"), problem);
	}

	@Test
	void anotherServersToolFails() {
		String problem = problem(init(List.of("mcp__aibuilder__fill", "mcp__github__create_issue"), OURS, "none", BUILT_IN));
		assertTrue(problem.contains("mcp__github__create_issue"), problem);
	}

	@Test
	void noBuildToolsFails() {
		assertTrue(InitCheck.problem(init(List.of(), OURS, "none", BUILT_IN)).isPresent());
	}

	@Test
	void anExtraServerFails() {
		String problem = problem(init(List.of("mcp__aibuilder__fill"),
				List.of(new StreamEvent.Server("aibuilder", "connected"), new StreamEvent.Server("github", "connected")), "none", BUILT_IN));
		assertTrue(problem.contains("github"), problem);
	}

	@Test
	void aDisconnectedServerFails() {
		String problem = problem(init(List.of("mcp__aibuilder__fill"), List.of(new StreamEvent.Server("aibuilder", "failed")), "none", BUILT_IN));
		assertTrue(problem.contains("aibuilder"), problem);
		assertTrue(InitCheck.problem(init(List.of("mcp__aibuilder__fill"), List.of(), "none", BUILT_IN)).isPresent());
	}

	@Test
	void anApiKeyFails() {
		String problem = problem(init(List.of("mcp__aibuilder__fill"), OURS, "ANTHROPIC_API_KEY", BUILT_IN));
		assertTrue(problem.contains("ANTHROPIC_API_KEY"), problem);
		assertTrue(InitCheck.problem(init(List.of("mcp__aibuilder__fill"), OURS, "", BUILT_IN)).isPresent(), "a missing source isn't the subscription");
	}

	@Test
	void aNonBuiltInPluginFails() {
		String problem = problem(init(List.of("mcp__aibuilder__fill"), OURS, "none",
				List.of(new StreamEvent.Plugin("cc-plugin-telemetry", "builtin"), new StreamEvent.Plugin("helper", "/home/someone/.claude/plugins/helper"))));
		assertTrue(problem.contains("helper"), problem);
	}
}
