package io.palmisano.aibuilder.core.request;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Commands a click runs (a book page, a sign, a tellraw link in a downloaded map) aren't typed: they may not send a
 * prompt through the owner's Claude login (M4 review). The mod's own buttons still work.
 */
class ClickedCommandsTest {
	@Test
	void promptsAndBuildsMustBeTyped() {
		assertTrue(ClickedCommands.refused("aibuild a castle"));
		assertTrue(ClickedCommands.refused("/aibuild a castle"));
		assertTrue(ClickedCommands.refused("aibuild test"));
		assertTrue(ClickedCommands.refused("AIBUILD a castle"));
	}

	@Test
	void theModsOwnButtonsAndOtherCommandsGoThrough() {
		assertFalse(ClickedCommands.refused("aibuild undo"));
		assertFalse(ClickedCommands.refused("/aibuild undo"));
		assertFalse(ClickedCommands.refused("aibuild cancel"));
		assertFalse(ClickedCommands.refused("aibuild status"));
		assertFalse(ClickedCommands.refused("aibuild survey"));
		assertFalse(ClickedCommands.refused("aibuild"));
		assertFalse(ClickedCommands.refused("tp @s 0 64 0"));
		assertFalse(ClickedCommands.refused("aibuilder something"));
	}

	@Test
	void aButtonWordWithMoreAfterItIsAPrompt() {
		assertTrue(ClickedCommands.refused("aibuild undo the castle and build a tower"));
	}
}
