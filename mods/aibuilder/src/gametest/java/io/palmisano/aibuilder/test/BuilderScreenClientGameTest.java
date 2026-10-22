package io.palmisano.aibuilder.test;

import static io.palmisano.aibuilder.test.GameTestSupport.check;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Function;

import com.mojang.blaze3d.platform.InputConstants;
import io.palmisano.aibuilder.client.AiBuilderClient;
import io.palmisano.aibuilder.client.BuilderScreen;
import io.palmisano.aibuilder.core.request.SizeTier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ChatScreen;

/** B opens the builder screen: it sends the prompt, remembers the size and recent prompts, and holds up at the edges. */
@SuppressWarnings("UnstableApiUsage")
public class BuilderScreenClientGameTest implements FabricClientGameTest {
	private static final String BARN = "a big barn by the bay";

	@Override
	public void runTest(ClientGameTestContext ctx) {
		writeHistory("{oops"); // a corrupt history file must not stop the screen from opening
		try (TestSingleplayerContext singleplayer = GameTestSupport.world(ctx)) {
			// B opens an empty screen.
			ctx.getInput().pressKey(AiBuilderClient.OPEN_BUILDER);
			ctx.waitForScreen(BuilderScreen.class);
			check(screen(ctx, BuilderScreen::prompt).isEmpty(), "a new screen should have an empty prompt");
			check(!screen(ctx, BuilderScreen::buildEnabled), "Build must be off while the prompt is empty");
			check(screen(ctx, BuilderScreen::size) == SizeTier.AUTO, "the size should start at Auto");
			check(screen(ctx, BuilderScreen::recentPrompts).isEmpty(), "the corrupt history file should read as empty");
			ctx.takeScreenshot("aibuilder-screen-empty");

			// Typing goes into the box, and the B key doesn't close or replace the screen.
			ctx.getInput().typeChars(BARN);
			check(screen(ctx, BuilderScreen::prompt).equals(BARN), "the box should hold the typed text, got " + screen(ctx, BuilderScreen::prompt));
			check(screen(ctx, BuilderScreen::buildEnabled), "Build should be on once there is a prompt");
			BuilderScreen opened = ctx.computeOnClient(mc -> (BuilderScreen) mc.gui.screen());
			ctx.getInput().pressKey(InputConstants.KEY_B);
			ctx.waitTicks(3);
			check(ctx.computeOnClient(mc -> mc.gui.screen() == opened), "pressing B in the builder replaced the screen");

			// The narrowest GUI vanilla allows (320x240) keeps the prompt and every widget on screen.
			int[] window = ctx.computeOnClient(mc -> new int[] {mc.getWindow().getWidth(), mc.getWindow().getHeight()});
			int guiWidth = screen(ctx, s -> s.width);
			ctx.getInput().resizeWindow(640, 480);
			ctx.waitFor(mc -> mc.gui.screen() != null && mc.gui.screen().width == 320);
			ctx.waitTick();
			check(screen(ctx, BuilderScreen::prompt).equals(BARN), "resizing the window lost the prompt");
			String offScreen = screen(ctx, BuilderScreenClientGameTest::firstWidgetOffScreen);
			check(offScreen == null, "widget off screen: " + offScreen);
			ctx.takeScreenshot("aibuilder-screen-small");
			ctx.getInput().resizeWindow(window[0], window[1]);
			ctx.waitFor(mc -> mc.gui.screen() != null && mc.gui.screen().width == guiWidth);

			// The size button cycles; Build sends the prompt and closes.
			ctx.clickScreenButton("gui.aibuilder.size");
			check(screen(ctx, BuilderScreen::size) == SizeTier.SMALL, "one click should move the size to Small");
			ctx.clickScreenButton("gui.aibuilder.build");
			ctx.waitFor(mc -> mc.gui.screen() == null);
			GameTestSupport.awaitChat(ctx, "AI Builder got: " + BARN + " (Small)");

			// Reopening remembers the size and lists the prompt; clicking it fills the box.
			ctx.getInput().pressKey(AiBuilderClient.OPEN_BUILDER);
			ctx.waitForScreen(BuilderScreen.class);
			check(screen(ctx, BuilderScreen::size) == SizeTier.SMALL, "the screen should remember Small");
			check(screen(ctx, BuilderScreen::recentPrompts).equals(List.of(BARN)), "recent prompts: " + screen(ctx, BuilderScreen::recentPrompts));
			check(screen(ctx, BuilderScreen::prompt).isEmpty(), "a reopened screen should start empty");
			ctx.clickScreenButton(BARN);
			check(screen(ctx, BuilderScreen::prompt).equals(BARN), "clicking a recent prompt should fill the box");
			ctx.takeScreenshot("aibuilder-screen-recent");

			// The box stops at 2,000 characters.
			ctx.getInput().typeChars("x".repeat(2100));
			int length = screen(ctx, s -> s.prompt().length());
			check(length == 2000, "the box should stop at 2,000 characters, got " + length);
			ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
			ctx.waitFor(mc -> mc.gui.screen() == null);

			// B while chat is open doesn't open the builder, neither then nor after chat closes.
			ctx.getInput().pressKey(options -> options.keyChat);
			ctx.waitForScreen(ChatScreen.class);
			ctx.getInput().pressKey(InputConstants.KEY_B);
			ctx.waitTicks(3);
			check(ctx.computeOnClient(mc -> mc.gui.screen() instanceof ChatScreen), "B over chat changed the screen");
			ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
			ctx.waitTicks(5);
			check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "a screen opened after chat closed");

			// A rebound key opens it.
			rebind(ctx, InputConstants.Type.KEYBOARD.getOrCreate(InputConstants.KEY_N));
			ctx.getInput().pressKey(InputConstants.KEY_N);
			ctx.waitForScreen(BuilderScreen.class);
			ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
			ctx.waitFor(mc -> mc.gui.screen() == null);
			rebind(ctx, AiBuilderClient.OPEN_BUILDER.getDefaultKey());
		}
	}

	private static <T> T screen(ClientGameTestContext ctx, Function<BuilderScreen, T> read) {
		return ctx.computeOnClient(mc -> read.apply((BuilderScreen) mc.gui.screen()));
	}

	/** The first widget not wholly inside the screen, described, or null when all fit. */
	private static String firstWidgetOffScreen(BuilderScreen s) {
		for (var child : s.children()) {
			if (child instanceof AbstractWidget w
					&& (w.getX() < 0 || w.getY() < 0 || w.getX() + w.getWidth() > s.width || w.getY() + w.getHeight() > s.height)) {
				return "\"" + w.getMessage().getString() + "\" at " + w.getX() + "," + w.getY() + " size " + w.getWidth() + "x"
						+ w.getHeight() + " on a " + s.width + "x" + s.height + " screen";
			}
		}
		return null;
	}

	private static void rebind(ClientGameTestContext ctx, InputConstants.Key key) {
		ctx.runOnClient(mc -> {
			AiBuilderClient.OPEN_BUILDER.setKey(key);
			KeyMapping.resetMapping();
		});
	}

	private static void writeHistory(String json) {
		try {
			Files.writeString(FabricLoader.getInstance().getConfigDir().resolve("aibuilder-history.json"), json);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
