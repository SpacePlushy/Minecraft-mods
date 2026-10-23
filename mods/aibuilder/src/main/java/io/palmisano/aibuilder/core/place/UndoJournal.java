package io.palmisano.aibuilder.core.place;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.palmisano.aibuilder.core.anim.MovingPart;
import io.palmisano.aibuilder.core.frame.Pos;

/**
 * What a build changed: for each position, what was there first (a block state {@code S} and block-entity data
 * {@code D}), and the block the build left with a hash of its block-entity data; and the entities it spawned (item
 * frames, spec §10, and moving parts' displays, spec v2 §4.1). Undo restores a position only while it still holds
 * that block with that data, so it never wipes what someone changed after the build.
 */
public final class UndoJournal<S, D> {
	public record Entry<S, D>(Pos pos, S original, D originalData, String writtenBlock, int writtenDataHash) {
	}

	/** An entity the build spawned: its id, where it is, the item it was given ("" for none), and its kind. */
	public record Spawned(UUID id, Pos pos, String item, String kind) {
		/** An item frame (spec §10); the kind of everything a save from before moving parts spawned. */
		public static final String FRAME = "item_frame";
		/** A moving part's block display (spec v2 §4.1), standing at its block's corner. */
		public static final String DISPLAY = "display";
	}

	/**
	 * A display an earlier build made, standing at {@code pos}, that this one showed another block on (a refinement):
	 * undo loads its chunk and shows {@code before} again.
	 */
	public record Reshown<S>(UUID display, Pos pos, S before) {
	}

	/** The motion a part of an earlier build had before this one gave it another (a refinement): undo gives it back. */
	public record Reanimated(UUID build, MovingPart before) {
	}

	private record PartOf(UUID build, String part) {
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
	private final List<Spawned> spawned = new ArrayList<>();
	private final Map<UUID, Reshown<S>> reshown = new LinkedHashMap<>();
	private final Map<PartOf, MovingPart> reanimated = new LinkedHashMap<>();
	private final Map<Pos, Boolean> levers = new LinkedHashMap<>();

	public static <S, D> UndoJournal<S, D> of(List<Entry<S, D>> entries) {
		return of(entries, List.of());
	}

	public static <S, D> UndoJournal<S, D> of(List<Entry<S, D>> entries, List<Spawned> spawned) {
		return of(entries, spawned, List.of(), List.of());
	}

	public static <S, D> UndoJournal<S, D> of(List<Entry<S, D>> entries, List<Spawned> spawned, List<Reshown<S>> reshown, List<Reanimated> reanimated) {
		UndoJournal<S, D> journal = new UndoJournal<>();
		for (Entry<S, D> e : entries) {
			journal.beforeWrite(e.pos(), e.original(), e.originalData());
			journal.afterWrite(e.pos(), e.writtenBlock(), e.writtenDataHash());
		}
		spawned.forEach(journal::addSpawned);
		reshown.forEach(r -> journal.reshown(r.display(), r.pos(), r.before()));
		reanimated.forEach(r -> journal.reanimated(r.build(), r.before()));
		return journal;
	}

	/** An earlier build's display showing another block now; the first block it showed before this build wins. */
	public void reshown(UUID display, Pos pos, S before) {
		reshown.putIfAbsent(display, new Reshown<>(display, pos, before));
	}

	/** In the order they were first shown another block. */
	public List<Reshown<S>> reshown() {
		return List.copyOf(reshown.values());
	}

	/** A lever the build placed, on or off as placed (spec v2 §5.1): undo puts it back so before the machine is taken down. */
	public void lever(Pos pos, boolean on) {
		levers.putIfAbsent(pos, on);
	}

	/** The build's levers as placed, in the order placed. */
	public Map<Pos, Boolean> levers() {
		return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(levers));
	}

	/** An earlier part's motion before this build gave it another; the first wins, as for blocks. */
	public void reanimated(UUID build, MovingPart before) {
		reanimated.putIfAbsent(new PartOf(build, before.name()), before);
	}

	/** In the order they were first given another motion. */
	public List<Reanimated> reanimated() {
		List<Reanimated> out = new ArrayList<>(reanimated.size());
		reanimated.forEach((part, before) -> out.add(new Reanimated(part.build(), before)));
		return out;
	}

	public void addSpawned(Spawned entity) {
		spawned.add(entity);
	}

	/** In the order they were spawned. */
	public List<Spawned> spawned() {
		return List.copyOf(spawned);
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

	/**
	 * Nothing to undo: no block changed, no entity spawned and nothing of an earlier build's moving parts changed (a
	 * build that only hung frames isn't empty, nor a refinement that only made the wings flap faster).
	 */
	public boolean isEmpty() {
		return records.isEmpty() && spawned.isEmpty() && reshown.isEmpty() && reanimated.isEmpty();
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
