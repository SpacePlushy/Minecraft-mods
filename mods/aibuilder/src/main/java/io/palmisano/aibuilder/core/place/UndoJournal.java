package io.palmisano.aibuilder.core.place;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.palmisano.aibuilder.core.frame.Pos;

/**
 * What a build changed: for each position, what was there first (a block state {@code S} and block-entity data
 * {@code D}), and the block the build left with a hash of its block-entity data. Undo restores a position only
 * while it still holds that block with that data, so it never wipes what someone changed after the build.
 */
public final class UndoJournal<S, D> {
	public record Entry<S, D>(Pos pos, S original, D originalData, String writtenBlock, int writtenDataHash) {
	}

	private static final class Record<S, D> {
		final S original;
		final D originalData;
		String writtenBlock;
		int writtenDataHash;

		Record(S original, D originalData) {
			this.original = original;
			this.originalData = originalData;
		}
	}

	private final Map<Pos, Record<S, D>> records = new LinkedHashMap<>();

	public static <S, D> UndoJournal<S, D> of(List<Entry<S, D>> entries) {
		UndoJournal<S, D> journal = new UndoJournal<>();
		for (Entry<S, D> e : entries) {
			journal.beforeWrite(e.pos(), e.original(), e.originalData());
			journal.afterWrite(e.pos(), e.writtenBlock(), e.writtenDataHash());
		}
		return journal;
	}

	/** Records what's at {@code pos} unless this build already changed it (the first original wins). */
	public void beforeWrite(Pos pos, S original, D originalData) {
		records.putIfAbsent(pos, new Record<>(original, originalData));
	}

	public void afterWrite(Pos pos, String blockId, int dataHash) {
		Record<S, D> record = records.get(pos);
		if (record == null) {
			throw new IllegalStateException("afterWrite without beforeWrite at " + pos);
		}
		record.writtenBlock = blockId;
		record.writtenDataHash = dataHash;
	}

	/** Whether this build changed {@code pos}. */
	public boolean has(Pos pos) {
		return records.containsKey(pos);
	}

	/** In the order they were first changed. */
	public List<Pos> positions() {
		return List.copyOf(records.keySet());
	}

	public S original(Pos pos) {
		return records.get(pos).original;
	}

	public D originalData(Pos pos) {
		return records.get(pos).originalData;
	}

	public String writtenBlock(Pos pos) {
		return records.get(pos).writtenBlock;
	}

	public int writtenDataHash(Pos pos) {
		return records.get(pos).writtenDataHash;
	}

	public int size() {
		return records.size();
	}

	public RestoreDecision decide(Pos pos, String currentBlockId, int currentDataHash) {
		Record<S, D> record = records.get(pos);
		boolean unchanged = record.writtenBlock != null && BlockEquivalence.same(currentBlockId, record.writtenBlock)
				&& currentDataHash == record.writtenDataHash;
		return unchanged ? RestoreDecision.RESTORE : RestoreDecision.KEEP;
	}

	public List<Entry<S, D>> entries() {
		List<Entry<S, D>> out = new ArrayList<>(records.size());
		records.forEach((pos, r) -> out.add(new Entry<>(pos, r.original, r.originalData, r.writtenBlock, r.writtenDataHash)));
		return out;
	}
}
