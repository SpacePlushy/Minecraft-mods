package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.palmisano.aibuilder.AiBuilderMod;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.UndoHistory;
import io.palmisano.aibuilder.core.place.UndoJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Each player's last {@link Tunables#UNDO_HISTORY} builds, saved with the world (spec §10), so {@code /aibuild undo}
 * works after a restart. A journal is stored compactly: positions as packed longs, original states and written
 * block ids as palettes with index arrays, and block-entity data only where there was some.
 */
public final class UndoStore extends SavedData {
	public record StoredBuild(ResourceKey<Level> dimension, String title, UndoJournal<BlockState, CompoundTag> journal) {
	}

	private record DataEntry(int index, CompoundTag tag) {
		static final Codec<DataEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.INT.fieldOf("index").forGetter(DataEntry::index),
				CompoundTag.CODEC.fieldOf("tag").forGetter(DataEntry::tag)).apply(i, DataEntry::new));
	}

	/** An entity the build spawned (an item frame), its position packed. */
	private static final Codec<UndoJournal.Spawned> SPAWNED_CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(UndoJournal.Spawned::id),
			Codec.LONG.fieldOf("pos").forGetter(s -> BlockPos.asLong(s.pos().x(), s.pos().y(), s.pos().z())),
			Codec.STRING.fieldOf("item").forGetter(UndoJournal.Spawned::item)).apply(i, (id, pos, item) -> {
				BlockPos at = BlockPos.of(pos);
				return new UndoJournal.Spawned(id, new Pos(at.getX(), at.getY(), at.getZ()), item);
			}));

	/**
	 * A journal as palettes and arrays, one slot per position in the order they were first changed, and the entities
	 * it spawned (none in a journal saved before M5).
	 */
	private record PackedJournal(long[] positions, List<BlockState> states, int[] stateIndex, List<String> written,
			int[] writtenIndex, int[] hashes, List<DataEntry> data, List<UndoJournal.Spawned> entities) {
		static final Codec<long[]> LONGS = Codec.LONG_STREAM.xmap(LongStream::toArray, LongStream::of);
		static final Codec<int[]> INTS = Codec.INT_STREAM.xmap(IntStream::toArray, IntStream::of);
		static final Codec<PackedJournal> CODEC = RecordCodecBuilder.create(i -> i.group(
				LONGS.fieldOf("positions").forGetter(PackedJournal::positions),
				BlockState.CODEC.listOf().fieldOf("states").forGetter(PackedJournal::states),
				INTS.fieldOf("state_index").forGetter(PackedJournal::stateIndex),
				Codec.STRING.listOf().fieldOf("written").forGetter(PackedJournal::written),
				INTS.fieldOf("written_index").forGetter(PackedJournal::writtenIndex),
				INTS.fieldOf("hashes").forGetter(PackedJournal::hashes),
				DataEntry.CODEC.listOf().fieldOf("data").forGetter(PackedJournal::data),
				SPAWNED_CODEC.listOf().optionalFieldOf("entities", List.of()).forGetter(PackedJournal::entities)).apply(i, PackedJournal::new));

		static PackedJournal pack(UndoJournal<BlockState, CompoundTag> journal) {
			List<UndoJournal.Entry<BlockState, CompoundTag>> entries = journal.entries();
			int n = entries.size();
			long[] positions = new long[n];
			int[] stateIndex = new int[n];
			int[] writtenIndex = new int[n];
			int[] hashes = new int[n];
			Map<BlockState, Integer> states = new LinkedHashMap<>();
			Map<String, Integer> written = new LinkedHashMap<>();
			List<DataEntry> data = new ArrayList<>();
			for (int k = 0; k < n; k++) {
				UndoJournal.Entry<BlockState, CompoundTag> e = entries.get(k);
				positions[k] = BlockPos.asLong(e.pos().x(), e.pos().y(), e.pos().z());
				stateIndex[k] = states.computeIfAbsent(e.original(), s -> states.size());
				// A position written without a recorded result stays unknown: "" keeps undo from touching it.
				writtenIndex[k] = written.computeIfAbsent(e.writtenBlock() == null ? "" : e.writtenBlock(), w -> written.size());
				hashes[k] = e.writtenDataHash();
				if (e.originalData() != null) {
					data.add(new DataEntry(k, e.originalData()));
				}
			}
			return new PackedJournal(positions, List.copyOf(states.keySet()), stateIndex, List.copyOf(written.keySet()), writtenIndex,
					hashes, data, journal.spawned());
		}

		UndoJournal<BlockState, CompoundTag> unpack() {
			int n = positions.length;
			if (stateIndex.length != n || writtenIndex.length != n || hashes.length != n) {
				throw new IllegalStateException("a saved undo journal has arrays of different lengths");
			}
			Map<Integer, CompoundTag> dataByIndex = new HashMap<>();
			data.forEach(d -> dataByIndex.put(d.index(), d.tag()));
			List<UndoJournal.Entry<BlockState, CompoundTag>> entries = new ArrayList<>(n);
			for (int k = 0; k < n; k++) {
				BlockPos pos = BlockPos.of(positions[k]);
				String block = written.get(writtenIndex[k]);
				entries.add(new UndoJournal.Entry<>(new Pos(pos.getX(), pos.getY(), pos.getZ()), states.get(stateIndex[k]),
						dataByIndex.get(k), block.isEmpty() ? null : block, hashes[k]));
			}
			return UndoJournal.of(entries, entities);
		}
	}

	private static final Codec<UndoJournal<BlockState, CompoundTag>> JOURNAL_CODEC = PackedJournal.CODEC.xmap(PackedJournal::unpack,
			PackedJournal::pack);

	private static final Codec<StoredBuild> BUILD_CODEC = RecordCodecBuilder.create(i -> i.group(
			Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(StoredBuild::dimension),
			Codec.STRING.fieldOf("title").forGetter(StoredBuild::title),
			JOURNAL_CODEC.fieldOf("journal").forGetter(StoredBuild::journal)).apply(i, StoredBuild::new));

	/** One player's builds, newest first. */
	private record PlayerBuilds(UUID player, List<StoredBuild> builds) {
		static final Codec<PlayerBuilds> CODEC = RecordCodecBuilder.create(i -> i.group(
				UUIDUtil.CODEC.fieldOf("player").forGetter(PlayerBuilds::player),
				BUILD_CODEC.listOf().fieldOf("builds").forGetter(PlayerBuilds::builds)).apply(i, PlayerBuilds::new));
	}

	public static final Codec<UndoStore> CODEC = PlayerBuilds.CODEC.listOf().fieldOf("players").codec()
			.xmap(UndoStore::fromPlayers, UndoStore::toPlayers);

	public static final SavedDataType<UndoStore> TYPE = new SavedDataType<>(AiBuilderMod.id("undo"), UndoStore::new, CODEC, null);

	/** The config's undo_history_size is kept at each push; a world saved with a bigger one loads whole. */
	private final UndoHistory<StoredBuild> history = new UndoHistory<>(Tunables.UNDO_HISTORY_CEILING);

	public UndoStore() {
	}

	/** Kept with the overworld's data, whichever dimension a build is in. */
	public static UndoStore get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	/** A build as it starts; it counts against undo_history_size once it has changed something ({@link #trim}). */
	public void push(UUID player, StoredBuild build) {
		history.push(player, build);
		setDirty();
	}

	/** Keeps the player's newest {@code keep} builds (the config's undo_history_size). */
	public void trim(UUID player, int keep) {
		history.trim(player, keep);
		setDirty();
	}

	/** Takes the player's newest build off their history. */
	public Optional<StoredBuild> pop(UUID player) {
		Optional<StoredBuild> build = history.pop(player);
		build.ifPresent(b -> setDirty());
		return build;
	}

	/** The player's builds that can still be undone, newest first. */
	public List<StoredBuild> builds(UUID player) {
		return List.copyOf(history.all().getOrDefault(player, new java.util.ArrayDeque<>()));
	}

	/** The player's newest build, left in place. */
	public Optional<StoredBuild> peek(UUID player) {
		return history.peek(player);
	}

	public void remove(UUID player, StoredBuild build) {
		history.remove(player, build);
		setDirty();
	}

	public void replace(UUID player, StoredBuild build, StoredBuild replacement) {
		history.replace(player, build, replacement);
		setDirty();
	}

	private static UndoStore fromPlayers(List<PlayerBuilds> players) {
		UndoStore store = new UndoStore();
		for (PlayerBuilds p : players) {
			// Saved newest first; pushing oldest first puts them back in the same order.
			for (StoredBuild build : p.builds().reversed()) {
				store.history.push(p.player(), build);
			}
		}
		return store;
	}

	private List<PlayerBuilds> toPlayers() {
		List<PlayerBuilds> players = new ArrayList<>();
		history.all().forEach((player, builds) -> players.add(new PlayerBuilds(player, List.copyOf(builds))));
		return players;
	}
}
