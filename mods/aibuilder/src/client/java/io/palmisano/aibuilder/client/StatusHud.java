package io.palmisano.aibuilder.client;

import java.util.List;

import io.palmisano.aibuilder.core.request.BuildStatus;
import io.palmisano.aibuilder.core.request.Messages;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The build's status panel (spec §5.3), top left, for the player whose build it is: the title, the phases with
 * ticks, blocks placed and a bar for the tool-call budget, fed once a second by the server. It goes when the server
 * clears it, when nothing has come for a few seconds (a lost connection, a crash), and in any other world than the
 * one it came from.
 */
public final class StatusHud implements HudElement {
	private static final long STALE_NANOS = 5_000_000_000L;
	private static final int MARGIN = 4;
	private static final int PAD = 4;
	private static final int LINE = 10;
	private static final int TEXT_WIDTH_MAX = 170;
	private static final int BAR_HEIGHT = 3;
	private static final int PANEL = 0x90000000;
	private static final int TITLE = 0xFFFFD45E;
	private static final int DONE = 0xFF7FD67F;
	private static final int UNDER_WAY = 0xFFFFFFFF;
	private static final int TO_COME = 0xFF9A9A9A;
	private static final int NOTE = 0xFF8FE3FF;
	private static final int COUNT = 0xFFDDDDDD;
	private static final int BAR_BACK = 0xFF3A3A3A;

	private static volatile BuildStatus status = BuildStatus.CLEAR;
	private static volatile long receivedAt;
	private static volatile ClientLevel receivedIn;

	/** Client thread: a status from the server. */
	static void show(BuildStatus next) {
		receivedIn = Minecraft.getInstance().level;
		receivedAt = System.nanoTime();
		status = next;
	}

	/** What the panel shows now; {@link BuildStatus#CLEAR} when it's down. */
	public static BuildStatus current() {
		BuildStatus shown = status;
		ClientLevel level = Minecraft.getInstance().level;
		if (shown.isClear() || level == null || level != receivedIn || System.nanoTime() - receivedAt > STALE_NANOS) {
			return BuildStatus.CLEAR;
		}
		return shown;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		BuildStatus shown = current();
		if (shown.isClear()) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		List<String> phases = shown.phaseLines();
		String placed = Messages.hudPlaced(shown.placed());
		String budget = Messages.hudBudget(shown.callsUsed(), shown.callsMax());
		int width = Math.max(font.width(shown.title()), Math.max(font.width(placed), font.width(budget)));
		for (String phase : phases) {
			width = Math.max(width, font.width(phase));
		}
		if (!shown.note().isEmpty()) {
			width = Math.max(width, font.width(shown.note()));
		}
		width = Math.min(width, TEXT_WIDTH_MAX);
		int lines = 3 + phases.size() + (shown.note().isEmpty() ? 0 : 1);
		int left = MARGIN + PAD;
		int top = MARGIN + PAD;
		graphics.fill(MARGIN, MARGIN, left + width + PAD, top + lines * LINE + BAR_HEIGHT + PAD, PANEL);

		int y = top;
		graphics.text(font, cut(font, shown.title()), left, y, TITLE, true);
		y += LINE;
		for (int i = 0; i < phases.size(); i++) {
			graphics.text(font, cut(font, phases.get(i)), left, y, i < shown.phase() ? DONE : i == shown.phase() ? UNDER_WAY : TO_COME, true);
			y += LINE;
		}
		if (!shown.note().isEmpty()) {
			graphics.text(font, cut(font, shown.note()), left, y, NOTE, true);
			y += LINE;
		}
		graphics.text(font, placed, left, y, COUNT, true);
		y += LINE;
		graphics.text(font, budget, left, y, COUNT, true);
		y += LINE;
		float used = shown.budgetUsed();
		graphics.fill(left, y, left + width, y + BAR_HEIGHT, BAR_BACK);
		graphics.fill(left, y, left + Math.round(width * used), y + BAR_HEIGHT, used < 0.7f ? 0xFF55C855 : used < 0.9f ? 0xFFE0C040 : 0xFFE05050);
	}

	private static String cut(Font font, String text) {
		return font.width(text) <= TEXT_WIDTH_MAX ? text : font.plainSubstrByWidth(text, TEXT_WIDTH_MAX - font.width("…"), false) + "…";
	}
}
