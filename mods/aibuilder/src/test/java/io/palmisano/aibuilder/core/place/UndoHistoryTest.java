package io.palmisano.aibuilder.core.place;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UndoHistoryTest {
	private final UUID frankie = UUID.randomUUID();
	private final UUID friend = UUID.randomUUID();

	@Test
	void historyIsNewestFirstAndCapped() {
		UndoHistory<Integer> history = new UndoHistory<>(5);
		for (int i = 1; i <= 6; i++) {
			history.push(frankie, i);
		}
		assertEquals(5, history.size(frankie));
		for (int expected = 6; expected >= 2; expected--) {
			assertEquals(Optional.of(expected), history.pop(frankie));
		}
		assertEquals(Optional.empty(), history.pop(frankie));
	}

	/** A running build sits in the history (saved with the world) without being taken off it. */
	/** undo_history_size (spec §14): each push keeps as many as the config asks for, never more than the ceiling. */
	@Test
	void pushKeepsAsManyAsAsked() {
		UndoHistory<Integer> history = new UndoHistory<>(50);
		for (int i = 1; i <= 12; i++) {
			history.push(frankie, i, 10);
		}
		assertEquals(10, history.size(frankie));
		history.push(frankie, 13, 3);
		assertEquals(3, history.size(frankie));
		assertEquals(Optional.of(13), history.peek(frankie));
		history.push(frankie, 14, 99);
		assertEquals(4, history.size(frankie));
	}

	@Test
	void peekLeavesTheNewestInPlace() {
		UndoHistory<String> history = new UndoHistory<>(5);
		history.push(frankie, "hut");
		history.push(frankie, "tower");
		assertEquals(Optional.of("tower"), history.peek(frankie));
		assertEquals(Optional.of("tower"), history.pop(frankie));
		assertEquals(Optional.empty(), history.peek(friend));
	}

	@Test
	void removeTakesOutThatBuildOnly() {
		UndoHistory<String> history = new UndoHistory<>(5);
		history.push(frankie, "hut");
		history.push(frankie, "tower");
		history.push(frankie, "wall");
		history.remove(frankie, "tower");
		assertEquals(Optional.of("wall"), history.pop(frankie));
		assertEquals(Optional.of("hut"), history.pop(frankie));
		assertEquals(Optional.empty(), history.pop(frankie));
	}

	@Test
	void replaceKeepsItsPlace() {
		UndoHistory<String> history = new UndoHistory<>(5);
		history.push(frankie, "hut");
		history.push(frankie, "tower");
		history.replace(frankie, "hut", "cabin");
		assertEquals(Optional.of("tower"), history.pop(frankie));
		assertEquals(Optional.of("cabin"), history.pop(frankie));
	}

	@Test
	void playersHaveSeparateHistories() {
		UndoHistory<String> history = new UndoHistory<>(5);
		history.push(frankie, "castle");
		history.push(friend, "hut");
		assertEquals(Optional.of("castle"), history.pop(frankie));
		assertEquals(Optional.empty(), history.pop(frankie));
		assertEquals(Optional.of("hut"), history.pop(friend));
	}
}
