package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.GameTestSupport.check;

import java.util.Map;

import io.palmisano.aibuilder.BuildManager;
import io.palmisano.aibuilder.TestHooks;
import io.palmisano.aibuilder.core.request.Messages;
import io.palmisano.aibuilder.core.request.SizeTier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * A real build from a prompt in the real game, with the fake Claude Code in place of the real one: the launch, the
 * start check, the MCP server, the tools, placement, the end messages, undo, the usage limit and a logged-out login.
 */
@SuppressWarnings("UnstableApiUsage")
public class ClaudeBuildClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext singleplayer = GameTestSupport.world(ctx)) {
			TestServerContext server = singleplayer.getServer();
			try {
				// The fake's build: get_context, submit_brief, a hollow 3×3×3 oak box, a checkpoint, finish.
				TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "build");
				GameTestSupport.runPlayerCommand(ctx, "aibuild a test hut");
				GameTestSupport.awaitChat(ctx, Messages.accepted("a test hut", SizeTier.AUTO));
				GameTestSupport.awaitChatStartingWith(ctx, "Designing: Test hut", 600);
				GameTestSupport.awaitChat(ctx, "A tiny oak hut.", 600);
				GameTestSupport.awaitChatStartingWith(ctx, "Done: ", 600);
				ctx.waitTicks(10);
				ctx.takeScreenshot("aibuilder-fake-claude-hut");
				GameTestSupport.runPlayerCommand(ctx, "aibuild undo");
				GameTestSupport.awaitChatStartingWith(ctx, "Undone: ", 600);

				// The plan's usage limit: the build stops at once and says when it resets.
				TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "usage_limit");
				GameTestSupport.runPlayerCommand(ctx, "aibuild a limited hut");
				GameTestSupport.awaitChatStartingWith(ctx, "Your Claude usage limit is reached (resets ", 600);

				// Logged out: the check before the launch says so, and nothing starts.
				TestHooks.claudeEnvironment = Map.of("FAKE_CLAUDE_SCENARIO", "build", "FAKE_CLAUDE_LOGGED_OUT", "1");
				GameTestSupport.runPlayerCommand(ctx, "aibuild a logged out hut");
				GameTestSupport.awaitChat(ctx, Messages.notLoggedIn(), 600);
				ctx.waitTicks(5);
				check(!server.computeOnServer(s -> BuildManager.of(s).busy()), "a build started without a login");
			} finally {
				TestHooks.claudeEnvironment = TestSetup.DEFAULT_SCENARIO;
			}
		}
	}
}
