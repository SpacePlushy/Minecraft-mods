package io.palmisano.aibuilder.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

import io.palmisano.aibuilder.TestHooks;
import io.palmisano.aibuilder.core.request.Messages;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

/** Shared setup and assertions for the AI Builder client gametests. */
@SuppressWarnings("UnstableApiUsage")
final class GameTestSupport {
	/** Every system message the client received since the last {@link #world}, as plain text, oldest first. */
	private static final List<String> CHAT = new CopyOnWriteArrayList<>();
	/** The action bar's lines since the last {@link #world}, oldest first. */
	private static final List<String> OVERLAY = new CopyOnWriteArrayList<>();

	static {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> (overlay ? OVERLAY : CHAT).add(message.getString()));
	}

	private GameTestSupport() {
	}

	/** A new singleplayer world with its chunks rendered, and an empty chat record. */
	static TestSingleplayerContext world(ClientGameTestContext ctx) {
		// A prompt in a test must never reach the real Claude Code on the owner's login (spec §17.5).
		check(TestHooks.claudeCommand != null || TestSetup.live(), "the fake Claude Code isn't installed: refusing to run a test that could"
				+ " start the real one");
		TestSingleplayerContext sp = ctx.worldBuilder().create();
		sp.getConnection().waitForChunksRender();
		CHAT.clear();
		OVERLAY.clear();
		return sp;
	}

	/** Runs a command as the player, as if typed in chat (no leading slash). */
	static void runPlayerCommand(ClientGameTestContext ctx, String command) {
		ctx.runOnClient(mc -> mc.player.connection.sendCommand(command));
	}

	/** Waits until chat has shown exactly {@code expected}; on timeout, fails listing what it did show. */
	static void awaitChat(ClientGameTestContext ctx, String expected) {
		awaitChat(ctx, expected, 100);
	}

	/** {@link #awaitChat}, waiting up to {@code maxTicks}. */
	static void awaitChat(ClientGameTestContext ctx, String expected, int maxTicks) {
		for (int i = 0; !CHAT.contains(expected); i++) {
			check(i < maxTicks, "chat never showed \"" + expected + "\"; it showed " + CHAT);
			ctx.waitTick();
		}
	}

	/** Waits until chat has shown a line starting with {@code prefix}; on timeout, fails listing what it did show. */
	static void awaitChatStartingWith(ClientGameTestContext ctx, String prefix) {
		awaitChatCount(ctx, prefix, 1, 100);
	}

	/** {@link #awaitChatStartingWith}, waiting up to {@code maxTicks}. */
	static void awaitChatStartingWith(ClientGameTestContext ctx, String prefix, int maxTicks) {
		awaitChatCount(ctx, prefix, 1, maxTicks);
	}

	/** Waits until chat has shown {@code count} lines starting with {@code prefix}. */
	static void awaitChatCount(ClientGameTestContext ctx, String prefix, int count, int maxTicks) {
		for (int i = 0; count(prefix) < count; i++) {
			check(i < maxTicks, "chat never showed " + count + " lines starting \"" + prefix + "\"; it showed " + CHAT);
			ctx.waitTick();
		}
	}

	/** How many lines so far start with {@code prefix}. */
	static int count(String prefix) {
		return (int) CHAT.stream().filter(line -> line.startsWith(prefix)).count();
	}

	/** How every build ends, one way or another. */
	static final List<String> BUILD_ENDS = List.of("Done: ", "Stopped", "Your Claude usage limit", Messages.extraUsage(), "Lost the connection",
			"Claude Code ", "Couldn't start Claude Code", Messages.notLoggedIn(), Messages.notInstalled(), Messages.stillStopping());

	/**
	 * The first line from chat line {@code from} on that ends a build, waiting up to {@code maxTicks}; {@code every}
	 * runs every {@code everyTicks} meanwhile (with the count so far), for progress screenshots of a long build.
	 */
	static String awaitBuildEnd(ClientGameTestContext ctx, int from, int maxTicks, int everyTicks, IntConsumer every) {
		for (int tick = 0; ; tick++) {
			List<String> chat = chat();
			for (String line : chat.subList(Math.min(from, chat.size()), chat.size())) {
				if (BUILD_ENDS.stream().anyMatch(line::startsWith)) {
					return line;
				}
			}
			check(tick < maxTicks, "the build never ended: " + chat);
			if (tick > 0 && tick % everyTicks == 0) {
				every.accept(tick / everyTicks);
			}
			ctx.waitTick();
		}
	}

	/** Every line so far, oldest first. */
	static List<String> chat() {
		return List.copyOf(CHAT);
	}

	/** Every action bar line so far, oldest first. */
	static List<String> overlay() {
		return List.copyOf(OVERLAY);
	}

	static void check(boolean ok, String msg) {
		if (!ok) {
			throw new AssertionError(msg);
		}
	}
}
