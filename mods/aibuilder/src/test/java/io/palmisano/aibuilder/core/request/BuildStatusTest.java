package io.palmisano.aibuilder.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import io.palmisano.aibuilder.core.Tunables;
import org.junit.jupiter.api.Test;

/** What the HUD panel shows (spec §5.3). */
class BuildStatusTest {
	@Test
	void phasesAreTickedUpToTheOneUnderWay() {
		BuildStatus status = new BuildStatus("Cottage", List.of("foundation", "walls", "roof"), 1, 120, 10, 150, "");
		assertEquals(List.of("✔ foundation", "▶ walls", "· roof"), status.phaseLines());
	}

	@Test
	void theBudgetBarFillsAndStopsFull() {
		assertEquals(0.25f, new BuildStatus("t", List.of(), 0, 0, 25, 100, "").budgetUsed());
		assertEquals(1f, new BuildStatus("t", List.of(), 0, 0, 160, 150, "").budgetUsed());
		assertEquals(0f, new BuildStatus("t", List.of(), 0, 0, 3, 0, "").budgetUsed());
	}

	/** Claude's text, so the panel bounds it: a short title and names, a dozen phases at most. */
	@Test
	void claudesTextIsBounded() {
		BuildStatus status = new BuildStatus("T".repeat(100), Collections.nCopies(30, "p".repeat(100)), 0, 0, 0, 1, "");
		assertEquals(Tunables.HUD_TEXT_MAX, status.title().length());
		assertEquals(Tunables.HUD_PHASES_MAX, status.phases().size());
		assertEquals(Tunables.HUD_TEXT_MAX, status.phases().getFirst().length());
	}

	/** Claude's text, shown as text: no colours or other formatting codes, no control characters. */
	@Test
	void formattingCodesAreDropped() {
		BuildStatus status = new BuildStatus("§cRed §lCastle", List.of("§kwalls\u0007", "roof\ttop"), 0, 0, 0, 1, "§rStarting\nClaude");
		assertEquals("Red Castle", status.title());
		assertEquals(List.of("walls", "roof top"), status.phases());
		assertEquals("Starting Claude", status.note());
	}

	@Test
	void anEmptyTitleClears() {
		assertTrue(BuildStatus.CLEAR.isClear());
		assertFalse(new BuildStatus("Cottage", List.of(), 0, 0, 0, 1, "").isClear());
	}

	/** A title that's nothing once plain (only formatting codes, or none yet) shows the untitled label: the panel stays up (the minors' review). */
	@Test
	void aTitleOfOnlyFormattingCodesKeepsThePanelUp() {
		BuildStatus codes = BuildStatus.showing("\u00a7a\u00a7l", "AI Builder", List.of(), 0, 0, 1, 150, "");
		assertFalse(codes.isClear());
		assertEquals("AI Builder", codes.title());
		assertEquals("AI Builder", BuildStatus.showing("", "AI Builder", List.of(), 0, 0, 1, 150, "").title());
		assertEquals("Castle", BuildStatus.showing("Castle", "AI Builder", List.of(), 0, 0, 1, 150, "").title());
	}
}
