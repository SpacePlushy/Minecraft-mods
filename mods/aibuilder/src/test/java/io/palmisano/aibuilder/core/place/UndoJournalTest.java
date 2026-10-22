package io.palmisano.aibuilder.core.place;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import io.palmisano.aibuilder.core.frame.Pos;
import org.junit.jupiter.api.Test;

class UndoJournalTest {
	private final Pos p = new Pos(1, 2, 3);
	private final UndoJournal<String, String> journal = new UndoJournal<>();

	private UndoJournal<String, String> wrote(String blockId, int dataHash) {
		journal.beforeWrite(p, "air", null);
		journal.afterWrite(p, blockId, dataHash);
		return journal;
	}

	@Test
	void firstWriteKeepsTheOriginal() {
		journal.beforeWrite(p, "grass", null);
		journal.afterWrite(p, "minecraft:stone", 0);
		journal.beforeWrite(p, "stone", null);
		assertEquals("grass", journal.original(p));
		assertEquals(1, journal.size());
	}

	@Test
	void lastWriteIsWhatCounts() {
		wrote("minecraft:stone", 0).afterWrite(p, "minecraft:oak_door", 0);
		assertEquals("minecraft:oak_door", journal.writtenBlock(p));
	}

	@Test
	void sameBlockIsRestoredWhateverItsToggles() {
		assertEquals(RestoreDecision.RESTORE, wrote("minecraft:oak_door", 0).decide(p, "minecraft:oak_door", 0));
	}

	@Test
	void aDifferentBlockIsKept() {
		assertEquals(RestoreDecision.KEEP, wrote("minecraft:stone", 0).decide(p, "minecraft:oak_planks", 0));
	}

	@Test
	void dirtLikeBlocksCountAsOne() {
		assertEquals(RestoreDecision.RESTORE, wrote("minecraft:dirt", 0).decide(p, "minecraft:grass_block", 0));
	}

	@Test
	void waterAndIceCountAsOne() {
		assertEquals(RestoreDecision.RESTORE, wrote("minecraft:water", 0).decide(p, "minecraft:ice", 0));
	}

	@Test
	void changedContentsAreKept() {
		assertEquals(RestoreDecision.KEEP, wrote("minecraft:chest", 11).decide(p, "minecraft:chest", 12));
	}

	@Test
	void entriesRebuildTheJournal() {
		wrote("minecraft:chest", 11);
		journal.beforeWrite(new Pos(0, 0, 0), "dirt", "tag");
		journal.afterWrite(new Pos(0, 0, 0), "minecraft:stone", 0);
		UndoJournal<String, String> copy = UndoJournal.of(journal.entries());
		assertEquals(journal.entries(), copy.entries());
		assertEquals(List.of(p, new Pos(0, 0, 0)), copy.positions());
		assertEquals("tag", copy.originalData(new Pos(0, 0, 0)));
	}
}
