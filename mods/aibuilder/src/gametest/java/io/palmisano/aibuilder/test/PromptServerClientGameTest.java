package io.palmisano.aibuilder.test;

import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.net.BuildPromptPayload;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** A prompt reaches the server from /aibuild and from the packet, and the server answers in chat. */
@SuppressWarnings("UnstableApiUsage")
public class PromptServerClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext singleplayer = GameTestSupport.world(ctx)) {
			GameTestSupport.runPlayerCommand(ctx, "aibuild hello world");
			GameTestSupport.awaitChat(ctx, "AI Builder got: hello world (Auto)");

			send(ctx, "castle", SizeTier.LARGE);
			GameTestSupport.awaitChat(ctx, "AI Builder got: castle (Large)");

			send(ctx, "  \n ", SizeTier.SMALL);
			GameTestSupport.awaitChat(ctx, "Type something for AI Builder to build.");

			send(ctx, "a".repeat(2001), SizeTier.AUTO);
			GameTestSupport.awaitChat(ctx, "That prompt is too long: 2,001 characters, the limit is 2,000.");

			send(ctx, "日本庭園 with a koi pond 🐟", SizeTier.MEDIUM);
			GameTestSupport.awaitChat(ctx, "AI Builder got: 日本庭園 with a koi pond 🐟 (Medium)");

			// Still connected after the over-long prompt: another command still gets an answer.
			GameTestSupport.runPlayerCommand(ctx, "aibuild still here");
			GameTestSupport.awaitChat(ctx, "AI Builder got: still here (Auto)");
		}
	}

	private static void send(ClientGameTestContext ctx, String prompt, SizeTier size) {
		ctx.runOnClient(mc -> ClientPlayNetworking.send(new BuildPromptPayload(prompt, size)));
	}
}
