package io.palmisano.aibuilder.client;

import java.util.List;

import io.palmisano.aibuilder.core.request.BuildPrompt;
import io.palmisano.aibuilder.core.request.PromptHistory;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.net.BuildPromptPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * The screen B opens: a prompt box, the size, Build and Cancel, and the recent prompts. 26.3 screens draw through
 * {@code extractRenderState}, so every label is a {@link StringWidget}. The typed text lives in {@link #draft} so a
 * resize, which runs {@link #init()} again, keeps it.
 */
public class BuilderScreen extends Screen {
	private static final int MAX_COLUMN_WIDTH = 320;
	private static final int BOX_TOP = 42;
	private static final int MAX_BOX_HEIGHT = 72;
	private static final int MIN_BOX_HEIGHT = 36;
	private static final int ROW_STEP = 22;

	private final PromptHistoryStore store;
	private PromptHistory history;
	private SizeTier size;
	private String draft = "";
	private MultiLineEditBox promptBox;
	private Button buildButton;

	public BuilderScreen(PromptHistoryStore store) {
		super(Component.translatable("gui.aibuilder.title"));
		this.store = store;
		this.history = store.load();
		this.size = history.lastSize();
	}

	@Override
	protected void init() {
		int column = Math.min(MAX_COLUMN_WIDTH, width - 40);
		int left = (width - column) / 2;

		addRenderableOnly(new StringWidget((width - font.width(title)) / 2, 12, font.width(title), font.lineHeight, title, font));
		addRenderableOnly(new StringWidget(left, 30, column, font.lineHeight, Component.translatable("gui.aibuilder.prompt"), font));

		int boxHeight = Math.clamp(height - 120, MIN_BOX_HEIGHT, MAX_BOX_HEIGHT);
		promptBox = MultiLineEditBox.builder()
				.setX(left)
				.setY(BOX_TOP)
				.setPlaceholder(Component.translatable("gui.aibuilder.placeholder"))
				.build(font, column, boxHeight, Component.translatable("gui.aibuilder.prompt"));
		promptBox.setCharacterLimit(BuildPrompt.MAX_CODE_POINTS);
		promptBox.setValue(draft);
		addRenderableWidget(promptBox);

		int sizeRow = BOX_TOP + boxHeight + 6;
		addRenderableWidget(CycleButton.builder((SizeTier tier) -> Component.literal(tier.label()), size)
				.withValues(SizeTier.values())
				.create(left, sizeRow, 100, 20, Component.translatable("gui.aibuilder.size"), (button, tier) -> chooseSize(tier)));
		addRenderableOnly(new StringWidget(left + 108, sizeRow + 6, column - 108, font.lineHeight,
				Component.translatable("gui.aibuilder.mode.new"), font));

		int buttonRow = sizeRow + 26;
		int half = (column - 4) / 2;
		buildButton = addRenderableWidget(Button.builder(Component.translatable("gui.aibuilder.build"), button -> build())
				.bounds(left, buttonRow, half, 20)
				.build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
				.bounds(left + column - half, buttonRow, half, 20)
				.build());

		addRecentPrompts(left, column, buttonRow + 30);

		promptBox.setValueListener(text -> {
			draft = text;
			buildButton.active = buildEnabled();
		});
		buildButton.active = buildEnabled();
	}

	/** The "Recent prompts" label and as many prompt buttons as end above {@code height - 8}; no label when none fit. */
	private void addRecentPrompts(int left, int column, int labelRow) {
		int firstRow = labelRow + 12;
		int room = height - 8 - firstRow;
		int fit = room < 20 ? 0 : 1 + (room - 20) / ROW_STEP;
		int shown = Math.min(fit, history.recent().size());
		if (shown == 0) {
			return;
		}
		addRenderableOnly(new StringWidget(left, labelRow, column, font.lineHeight, Component.translatable("gui.aibuilder.recent"), font));
		for (int i = 0; i < shown; i++) {
			String prompt = history.recent().get(i);
			addRenderableWidget(Button.builder(Component.literal(buttonLabel(prompt, column - 8)), button -> useRecent(prompt))
					.bounds(left, firstRow + i * ROW_STEP, column, 20)
					.build());
		}
	}

	/** The prompt's first line, cut to {@code maxWidth} pixels with "…" when it doesn't fit. */
	private String buttonLabel(String prompt, int maxWidth) {
		String line = prompt.lines().findFirst().orElse("");
		if (font.width(line) <= maxWidth) {
			return line;
		}
		return font.plainSubstrByWidth(line, maxWidth - font.width("…")) + "…";
	}

	@Override
	protected void setInitialFocus() {
		setInitialFocus(promptBox);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void useRecent(String prompt) {
		promptBox.setValue(prompt);
		draft = prompt;
		buildButton.active = buildEnabled();
		setFocused(promptBox);
	}

	private void chooseSize(SizeTier tier) {
		size = tier;
		history = history.withSize(tier);
		store.save(history);
	}

	private void build() {
		if (!ClientPlayNetworking.canSend(BuildPromptPayload.TYPE)) {
			minecraft.player.sendSystemMessage(Component.translatable("gui.aibuilder.no_server_mod"));
			onClose();
			return;
		}
		ClientPlayNetworking.send(new BuildPromptPayload(draft, size));
		history = history.withPrompt(draft).withSize(size);
		store.save(history);
		onClose();
	}

	public String prompt() {
		return draft;
	}

	public SizeTier size() {
		return size;
	}

	public boolean buildEnabled() {
		return !BuildPrompt.normalize(draft).isEmpty();
	}

	public List<String> recentPrompts() {
		return history.recent();
	}
}
