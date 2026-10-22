package io.palmisano.aibuilder.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/** Shared setup and assertions for the AI Builder client gametests. */
@SuppressWarnings("UnstableApiUsage")
final class GameTestSupport {
	/** Every system message the client received since the last {@link #world}, as plain text, oldest first. */
	private static final List<String> CHAT = new CopyOnWriteArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				CHAT.add(message.getString());
			}
		});
	}

	private GameTestSupport() {
	}

	/** A new singleplayer world with its chunks rendered, and an empty chat record. */
	static TestSingleplayerContext world(ClientGameTestContext ctx) {
		TestSingleplayerContext sp = ctx.worldBuilder().create();
		sp.getConnection().waitForChunksRender();
		CHAT.clear();
		return sp;
	}

	/** Runs a command as the player, as if typed in chat (no leading slash). */
	static void runPlayerCommand(ClientGameTestContext ctx, String command) {
		ctx.runOnClient(mc -> mc.player.connection.sendCommand(command));
	}

	/** Waits until chat has shown exactly {@code expected}; on timeout, fails listing what it did show. */
	static void awaitChat(ClientGameTestContext ctx, String expected) {
		for (int i = 0; !CHAT.contains(expected); i++) {
			check(i < 100, "chat never showed \"" + expected + "\"; it showed " + CHAT);
			ctx.waitTick();
		}
	}

	/** Waits until chat has shown a line starting with {@code prefix}; on timeout, fails listing what it did show. */
	static void awaitChatStartingWith(ClientGameTestContext ctx, String prefix) {
		for (int i = 0; CHAT.stream().noneMatch(line -> line.startsWith(prefix)); i++) {
			check(i < 100, "chat never showed a line starting \"" + prefix + "\"; it showed " + CHAT);
			ctx.waitTick();
		}
	}

	static void check(boolean ok, String msg) {
		if (!ok) {
			throw new AssertionError(msg);
		}
	}
}
