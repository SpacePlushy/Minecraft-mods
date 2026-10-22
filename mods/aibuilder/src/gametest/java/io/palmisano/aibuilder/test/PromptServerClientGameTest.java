package io.palmisano.aibuilder.test;

import io.palmisano.aibuilder.core.request.Messages;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.net.BuildPromptPayload;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * A prompt reaches the server from /aibuild and from the packet and starts a build; here the fake Claude Code
 * (TestSetup) just answers with the prompt, so each build ends at once with the prompt as its summary.
 */
@SuppressWarnings("UnstableApiUsage")
public class PromptServerClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext singleplayer = GameTestSupport.world(ctx)) {
			GameTestSupport.runPlayerCommand(ctx, "aibuild hello world");
			built(ctx, "hello world", SizeTier.AUTO, 1);

			send(ctx, "castle", SizeTier.LARGE);
			built(ctx, "castle", SizeTier.LARGE, 2);

			send(ctx, "  \n ", SizeTier.SMALL);
			GameTestSupport.awaitChat(ctx, "Type something for AI Builder to build.");

			send(ctx, "a".repeat(2001), SizeTier.AUTO);
			GameTestSupport.awaitChat(ctx, "That prompt is too long: 2,001 characters, the limit is 2,000.");

			send(ctx, "日本庭園 with a koi pond 🐟", SizeTier.MEDIUM);
			built(ctx, "日本庭園 with a koi pond 🐟", SizeTier.MEDIUM, 3);

			// Still connected after the over-long prompt: another command still gets an answer.
			GameTestSupport.runPlayerCommand(ctx, "aibuild still here");
			built(ctx, "still here", SizeTier.AUTO, 4);
		}
	}

	/** The prompt was taken, Claude (the fake) answered with it, and the build ended: the {@code n}th "Done:". */
	private static void built(ClientGameTestContext ctx, String prompt, SizeTier size, int n) {
		GameTestSupport.awaitChat(ctx, Messages.accepted(prompt, size));
		GameTestSupport.awaitChat(ctx, prompt, 600);
		GameTestSupport.awaitChatCount(ctx, "Done: ", n, 600);
	}

	private static void send(ClientGameTestContext ctx, String prompt, SizeTier size) {
		ctx.runOnClient(mc -> ClientPlayNetworking.send(new BuildPromptPayload(prompt, size)));
	}
}
