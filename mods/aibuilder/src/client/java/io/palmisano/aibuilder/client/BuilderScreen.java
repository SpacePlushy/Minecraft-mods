package io.palmisano.aibuilder.client;

import java.util.ArrayList;
import java.util.List;

import io.palmisano.aibuilder.core.request.BuildPrompt;
import io.palmisano.aibuilder.core.request.BuildStatus;
import io.palmisano.aibuilder.core.request.Messages;
import io.palmisano.aibuilder.core.request.PromptHistory;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.net.BuildModePayload;
import io.palmisano.aibuilder.net.BuildPromptPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
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
	private static final int MAX_BOX_HEIGHT = 63;
	private static final int MIN_BOX_HEIGHT = 36;
	private static final int ROW_STEP = 22;

	private final PromptHistoryStore store;
	private PromptHistory history;
	private SizeTier size;
	private String draft = "";
	private int recentOffset;
	private MultiLineEditBox promptBox;
	private Button buildButton;
	private final List<Button> recentRows = new ArrayList<>();
	private Button upButton;
	private Button downButton;
	private Button modeButton;
	/** The title a prompt from here would refine, as the server last said; "" for a new build. Client thread. */
	private static String refining = "";
	/** The mode toggle: a new build here even next to the last one (spec §5.1). */
	private boolean forceNew;
	/** A build of the player's is running (its status panel is up): Build is "Stop build" (spec §5.1). */
	private boolean running;
	/** While it runs, the status line in place of the prompt's label. */
	private StringWidget statusWidget;

	public BuilderScreen(PromptHistoryStore store) {
		super(Component.translatable("gui.aibuilder.title"));
		this.store = store;
		this.history = store.load();
		this.size = history.lastSize();
		// Asked again each time the screen opens: where the player stands decides it.
		refining = "";
		if (ClientPlayNetworking.canSend(BuildModePayload.Query.TYPE)) {
			ClientPlayNetworking.send(new BuildModePayload.Query());
		}
	}

	/** The server's answer to the mode question. Client thread. */
	static void refining(String title) {
		refining = title;
		if (Minecraft.getInstance().gui.screen() instanceof BuilderScreen screen && screen.modeButton != null) {
			screen.showMode();
		}
	}

	@Override
	protected void init() {
		int column = Math.min(MAX_COLUMN_WIDTH, width - 40);
		int left = (width - column) / 2;

		addRenderableOnly(new StringWidget((width - font.width(title)) / 2, 12, font.width(title), font.lineHeight, title, font));
		running = !StatusHud.current().isClear();
		statusWidget = null;
		if (running) {
			statusWidget = addRenderableOnly(new StringWidget(left, 30, column, font.lineHeight, Component.literal(buttonLabel(statusLine(), column)), font));
		} else {
			addRenderableOnly(new StringWidget(left, 30, column, font.lineHeight, Component.translatable("gui.aibuilder.prompt"), font));
		}

		// The box draws its n/2,000 counter 4 below its right corner, a line high; the rows under it start past that.
		int counterRoom = 4 + font.lineHeight + 2;
		int boxHeight = Math.clamp(height - 114 - counterRoom, MIN_BOX_HEIGHT, MAX_BOX_HEIGHT);
		promptBox = MultiLineEditBox.builder()
				.setX(left)
				.setY(BOX_TOP)
				.setPlaceholder(Component.translatable("gui.aibuilder.placeholder"))
				.build(font, column, boxHeight, Component.translatable("gui.aibuilder.prompt"));
		promptBox.setCharacterLimit(BuildPrompt.MAX_CODE_POINTS);
		promptBox.setValue(draft);
		addRenderableWidget(promptBox);

		int sizeRow = BOX_TOP + boxHeight + counterRoom;
		addRenderableWidget(CycleButton.builder((SizeTier tier) -> Component.literal(tier.label()), size)
				.withValues(SizeTier.values())
				.create(left, sizeRow, 100, 20, Component.translatable("gui.aibuilder.size"), (button, tier) -> chooseSize(tier)));
		modeButton = addRenderableWidget(Button.builder(Component.empty(), button -> toggleMode())
				.bounds(left + 108, sizeRow, column - 108, 20)
				.build());
		showMode();

		int buttonRow = sizeRow + 26;
		int half = (column - 4) / 2;
		buildButton = addRenderableWidget(Button.builder(Component.translatable(running ? "gui.aibuilder.stop" : "gui.aibuilder.build"),
				button -> {
					if (running) {
						stop();
					} else {
						build();
					}
				})
				.bounds(left, buttonRow, half, 20)
				.build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose())
				.bounds(left + column - half, buttonRow, half, 20)
				.build());

		addRecentPrompts(left, column, buttonRow + 30);

		promptBox.setValueListener(text -> {
			draft = text;
			buildButton.active = running || buildEnabled();
		});
		buildButton.active = running || buildEnabled();
	}

	/** A build starting or ending while the screen is open turns the button over; the status line keeps up. */
	@Override
	public void tick() {
		super.tick();
		if (StatusHud.current().isClear() == running) {
			rebuildWidgets();
		} else if (statusWidget != null) {
			statusWidget.setMessage(Component.literal(buttonLabel(statusLine(), statusWidget.getWidth())));
		}
	}

	/** What the running build is doing, as the status panel has it; "" when nothing runs. */
	public String statusLine() {
		BuildStatus status = StatusHud.current();
		if (status.isClear()) {
			return "";
		}
		String title = status.title().equals(Messages.hudUntitled()) ? "" : status.title();
		String phase = !status.note().isEmpty() ? status.note()
				: status.phase() >= 0 && status.phase() < status.phases().size() ? status.phases().get(status.phase()) : "";
		return Messages.screenStatus(title, phase, status.placed());
	}

	/** As /aibuild cancel, typed: it goes through the same checks. */
	private void stop() {
		minecraft.player.connection.sendCommand("aibuild cancel");
		onClose();
	}

	/**
	 * The "Recent prompts" label and as many prompt rows as end above {@code height - 8}; when the history holds more
	 * than fit, ▲ and ▼ beside the label scroll the rows through all of it. No label when no row fits.
	 */
	private void addRecentPrompts(int left, int column, int labelRow) {
		recentRows.clear();
		upButton = null;
		downButton = null;
		int firstRow = labelRow + 12;
		int room = height - 8 - firstRow;
		int fit = room < 20 ? 0 : 1 + (room - 20) / ROW_STEP;
		int shown = Math.min(fit, history.recent().size());
		if (shown == 0) {
			return;
		}
		recentOffset = Math.clamp(recentOffset, 0, history.recent().size() - shown);
		boolean scrolls = history.recent().size() > shown;
		addRenderableOnly(new StringWidget(left, labelRow, scrolls ? column - 48 : column, font.lineHeight,
				Component.translatable("gui.aibuilder.recent"), font));
		if (scrolls) {
			upButton = addRenderableWidget(Button.builder(Component.translatable("gui.aibuilder.recent.up"), button -> scrollRecent(-1))
					.bounds(left + column - 44, labelRow - 2, 20, 12)
					.build());
			downButton = addRenderableWidget(Button.builder(Component.translatable("gui.aibuilder.recent.down"), button -> scrollRecent(1))
					.bounds(left + column - 20, labelRow - 2, 20, 12)
					.build());
		}
		for (int i = 0; i < shown; i++) {
			int row = i;
			recentRows.add(addRenderableWidget(Button.builder(Component.empty(), button -> useRecent(row))
					.bounds(left, firstRow + i * ROW_STEP, column, 20)
					.build()));
		}
		showRecentRows();
	}

	private void scrollRecent(int delta) {
		recentOffset = Math.clamp(recentOffset + delta, 0, history.recent().size() - recentRows.size());
		showRecentRows();
	}

	/** Labels the rows from {@link #recentOffset} on, and greys out an arrow at that end of the list. */
	private void showRecentRows() {
		for (int i = 0; i < recentRows.size(); i++) {
			Button row = recentRows.get(i);
			row.setMessage(Component.literal(buttonLabel(history.recent().get(recentOffset + i), row.getWidth() - 8)));
		}
		if (upButton != null) {
			upButton.active = recentOffset > 0;
			downButton.active = recentOffset + recentRows.size() < history.recent().size();
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

	/** Puts the prompt in that row back in the box; the box's listener updates the draft and Build. */
	private void useRecent(int row) {
		promptBox.setValue(history.recent().get(recentOffset + row));
		setFocused(promptBox);
	}

	private void toggleMode() {
		forceNew = !forceNew;
		showMode();
	}

	/** "Refining: <title>", or "New build here"; the button toggles between them only when there's a build to refine. */
	private void showMode() {
		modeButton.setMessage(Component.literal(buttonLabel(modeLine(), modeButton.getWidth() - 8)));
		modeButton.active = !refining.isEmpty();
	}

	public String modeLine() {
		return refining.isEmpty() || forceNew ? Component.translatable("gui.aibuilder.mode.new").getString()
				: Component.translatable("gui.aibuilder.mode.refine", refining).getString();
	}

	public boolean forceNew() {
		return forceNew;
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
		ClientPlayNetworking.send(new BuildPromptPayload(draft, size, forceNew && !refining.isEmpty()));
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
