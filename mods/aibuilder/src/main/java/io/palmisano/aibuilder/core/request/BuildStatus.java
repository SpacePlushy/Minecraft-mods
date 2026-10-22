package io.palmisano.aibuilder.core.request;

import java.util.ArrayList;
import java.util.List;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.blocks.BlockEntityData;

/**
 * What the HUD panel shows (spec §5.3), sent once a second while a build runs: the brief's title and phases, the
 * one under way ({@code phase}, the number done), blocks placed, the tool-call budget, and the backend's note
 * ("Starting Claude…") when there is one. {@link #CLEAR}, with no title, hides the panel.
 */
public record BuildStatus(String title, List<String> phases, int phase, int placed, int callsUsed, int callsMax, String note) {
	public static final BuildStatus CLEAR = new BuildStatus("", List.of(), 0, 0, 0, 0, "");

	public BuildStatus {
		title = cut(title);
		phases = phases.stream().limit(Tunables.HUD_PHASES_MAX).map(BuildStatus::cut).toList();
		note = cut(note);
	}

	public boolean isClear() {
		return title.isEmpty();
	}

	/** Each phase as the panel lists it: the ones done ticked, the one under way marked. */
	public List<String> phaseLines() {
		List<String> out = new ArrayList<>(phases.size());
		for (int i = 0; i < phases.size(); i++) {
			out.add((i < phase ? "✔ " : i == phase ? "▶ " : "· ") + phases.get(i));
		}
		return out;
	}

	/** The share of the tool-call budget used, 0 to 1. */
	public float budgetUsed() {
		return callsMax <= 0 ? 0 : Math.min(1f, (float) callsUsed / callsMax);
	}

	/** Claude's text as plain text (no formatting codes or control characters), at most HUD_TEXT_MAX characters. */
	private static String cut(String text) {
		String plain = BlockEntityData.plain(text, Integer.MAX_VALUE, false);
		return plain.codePointCount(0, plain.length()) <= Tunables.HUD_TEXT_MAX ? plain
				: plain.substring(0, plain.offsetByCodePoints(0, Tunables.HUD_TEXT_MAX));
	}
}
