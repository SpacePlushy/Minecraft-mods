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
import io.palmisano.aibuilder.core.request.BuildPrompt;
import io.palmisano.aibuilder.core.request.Messages;
import io.palmisano.aibuilder.core.request.SizeTier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.NarratorStatus;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

/** B opens the builder screen: it sends the prompt, remembers the size and recent prompts, and holds up at the edges. */
@SuppressWarnings("UnstableApiUsage")
public class BuilderScreenClientGameTest implements FabricClientGameTest {
	private static final String BARN = "a big barn by the bay";

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestSetup.live()) {
			return; // a live run is for LiveBuildClientGameTest alone
		}
		writeHistory("{oops"); // a corrupt history file must not stop the screen from opening
		try (TestSingleplayerContext singleplayer = GameTestSupport.world(ctx)) {
			// A held Ctrl (the sprint key, on a Mac not the narrator's) doesn't keep the builder shut (final review).
			ctx.getInput().holdKey(InputConstants.KEY_LCONTROL);
			ctx.getInput().pressKey(InputConstants.KEY_B);
			ctx.getInput().releaseKey(InputConstants.KEY_LCONTROL);
			ctx.waitForScreen(BuilderScreen.class);
			ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
			ctx.waitFor(mc -> mc.gui.screen() == null);

			// B opens an empty screen.
			ctx.getInput().pressKey(InputConstants.KEY_B);
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
			String overCounter = screen(ctx, BuilderScreenClientGameTest::firstWidgetOverCounter);
			check(overCounter == null, "widget over the character counter: " + overCounter);
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
			String overCounterSmall = screen(ctx, BuilderScreenClientGameTest::firstWidgetOverCounter);
			check(overCounterSmall == null, "widget over the character counter at 320x240: " + overCounterSmall);
			ctx.takeScreenshot("aibuilder-screen-small");
			ctx.getInput().resizeWindow(window[0], window[1]);
			ctx.waitFor(mc -> mc.gui.screen() != null && mc.gui.screen().width == guiWidth);

			// The size button cycles; Build sends the prompt and closes.
			ctx.clickScreenButton("gui.aibuilder.size");
			check(screen(ctx, BuilderScreen::size) == SizeTier.SMALL, "one click should move the size to Small");
			ctx.clickScreenButton("gui.aibuilder.build");
			ctx.waitFor(mc -> mc.gui.screen() == null);
			GameTestSupport.awaitChat(ctx, Messages.accepted(BARN, SizeTier.SMALL));
			// The fake Claude Code answers with the prompt, so the build ends at once.
			GameTestSupport.awaitChatStartingWith(ctx, "Done: ", 600);

			// Reopening remembers the size and lists the prompt; clicking it fills the box.
			ctx.getInput().pressKey(AiBuilderClient.OPEN_BUILDER);
			ctx.waitForScreen(BuilderScreen.class);
			check(screen(ctx, BuilderScreen::size) == SizeTier.SMALL, "the screen should remember Small");
			check(screen(ctx, BuilderScreen::recentPrompts).equals(List.of(BARN)), "recent prompts: " + screen(ctx, BuilderScreen::recentPrompts));
			check(screen(ctx, BuilderScreen::prompt).isEmpty(), "a reopened screen should start empty");
			ctx.clickScreenButton(BARN);
			check(screen(ctx, BuilderScreen::prompt).equals(BARN), "clicking a recent prompt should fill the box");
			ctx.takeScreenshot("aibuilder-screen-recent");

			// The box stops at 2,000 characters. An emoji typed at the limit is cut in half there; Build sends the prompt
			// without the half (it would arrive as "?").
			String full = BARN + "x".repeat(1999 - BARN.length());
			ctx.getInput().typeChars(full.substring(BARN.length()) + "🏰" + "x".repeat(100));
			int length = screen(ctx, s -> s.prompt().length());
			check(length == 2000, "the box should stop at 2,000 characters, got " + length);
			ctx.clickScreenButton("gui.aibuilder.build");
			ctx.waitFor(mc -> mc.gui.screen() == null);
			GameTestSupport.awaitChat(ctx, Messages.accepted(full, SizeTier.SMALL));
			GameTestSupport.awaitChatCount(ctx, "Done: ", 2, 600);

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

			// All ten stored prompts are reachable: the newest show first, the arrows reach the oldest. A prompt of more than
			// one line shows its first, with "…".
			writeHistory("{\"lastSize\":\"AUTO\",\"recent\":[\"p10\\nwith a flag\",\"p9\",\"p8\",\"p7\",\"p6\",\"p5\",\"p4\",\"p3\",\"p2\",\"p1\"]}");
			ctx.getInput().pressKey(AiBuilderClient.OPEN_BUILDER);
			ctx.waitForScreen(BuilderScreen.class);
			check(hasButton(ctx, "p10…") && hasButton(ctx, "p9") && !hasButton(ctx, "p1"), "the newest prompts should show first, a"
					+ " prompt of two lines as \"p10…\"");
			for (int i = 0; i < 8; i++) {
				ctx.clickScreenButton("gui.aibuilder.recent.down");
			}
			check(hasButton(ctx, "p1"), "the arrows should reach the oldest prompt");
			ctx.clickScreenButton("p1");
			check(screen(ctx, BuilderScreen::prompt).equals("p1"), "clicking the oldest prompt should fill the box");
			ctx.clickScreenButton("gui.aibuilder.recent.up");
			check(hasButton(ctx, "p3"), "the up arrow should scroll back");

			// The narrowest GUI with a full list keeps every widget, arrows and prompt rows included, on screen.
			ctx.getInput().resizeWindow(640, 480);
			ctx.waitFor(mc -> mc.gui.screen() != null && mc.gui.screen().width == 320);
			ctx.waitTick();
			String offScreenWithList = screen(ctx, BuilderScreenClientGameTest::firstWidgetOffScreen);
			check(offScreenWithList == null, "widget off screen with ten recent prompts: " + offScreenWithList);
			ctx.takeScreenshot("aibuilder-screen-scroll");
			ctx.getInput().resizeWindow(window[0], window[1]);
			ctx.waitFor(mc -> mc.gui.screen() != null && mc.gui.screen().width == guiWidth);
			ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
			ctx.waitFor(mc -> mc.gui.screen() == null);

			// The press that toggles the narrator (Ctrl+B; Cmd+B on macOS) is the narrator's, not the builder's. The
			// test's key events carry no modifiers, so vanilla never toggles it here: the test does, as that press would,
			// then puts it back. Last, as the narrator's toast would show in a screenshot.
			NarratorStatus narrator = ctx.computeOnClient(mc -> mc.options.narrator().get());
			ctx.runOnClient(mc -> mc.options.narrator().set(NarratorStatus.byId(narrator.getId() + 1)));
			ctx.getInput().pressKey(InputConstants.KEY_B);
			ctx.waitTicks(3);
			check(ctx.computeOnClient(mc -> mc.gui.screen() == null), "the narrator's shortcut opened the builder");
			ctx.runOnClient(mc -> mc.options.narrator().set(narrator));
			ctx.waitTick();
		}
	}

	/** Whether the open screen has a button labelled exactly {@code label}. */
	private static boolean hasButton(ClientGameTestContext ctx, String label) {
		return ctx.computeOnClient(mc -> mc.gui.screen().children().stream()
				.anyMatch(child -> child instanceof AbstractWidget w && w.getMessage().getString().equals(label)));
	}

	private static <T> T screen(ClientGameTestContext ctx, Function<BuilderScreen, T> read) {
		return ctx.computeOnClient(mc -> read.apply((BuilderScreen) mc.gui.screen()));
	}

	/** The first widget over the box's n/2,000 counter (drawn 4 below the box's right corner, a line high), or null. */
	private static String firstWidgetOverCounter(BuilderScreen s) {
		Font font = Minecraft.getInstance().font;
		MultiLineEditBox box = s.children().stream().filter(c -> c instanceof MultiLineEditBox).map(c -> (MultiLineEditBox) c).findFirst().orElseThrow();
		String counter = Component.translatable("gui.multiLineEditBox.character_limit", s.prompt().length(), BuildPrompt.MAX_CODE_POINTS).getString();
		int top = box.getY() + box.getHeight() + 4;
		int bottom = top + font.lineHeight;
		int right = box.getX() + box.getWidth();
		int left = right - font.width(counter);
		for (var child : s.children()) {
			if (child != box && child instanceof AbstractWidget w && w.getX() < right && w.getX() + w.getWidth() > left && w.getY() < bottom
					&& w.getY() + w.getHeight() > top) {
				return "\"" + w.getMessage().getString() + "\" at " + w.getX() + "," + w.getY() + ", the counter at " + left + "," + top;
			}
		}
		return null;
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
