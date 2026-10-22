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
