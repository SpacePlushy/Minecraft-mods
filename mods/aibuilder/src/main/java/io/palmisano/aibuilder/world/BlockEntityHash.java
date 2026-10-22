package io.palmisano.aibuilder.world;

import java.util.List;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * What undo compares to tell the build's own block entity from one the player has changed since: a hash of its saved
 * data, leaving out what a block entity rewrites by itself as time passes.
 */
final class BlockEntityHash {
	/** Paths into the saved data that change on their own; a change there isn't the player's doing. */
	private static final List<List<String>> CHANGES_BY_ITSELF = List.of(
			// A vault's timer for its next look around for players.
			List.of("server_data", "state_updating_resumes_at"));

	private BlockEntityHash() {
	}

	/** 0 when there's no block entity. */
	static int of(BlockEntity entity, HolderLookup.Provider registries) {
		return entity == null ? 0 : of(entity.saveWithFullMetadata(registries));
	}

	/** The same hash for saved data, such as a journal's original. */
	static int of(CompoundTag data) {
		CompoundTag kept = data.copy();
		for (List<String> path : CHANGES_BY_ITSELF) {
			CompoundTag parent = kept;
			for (int i = 0; i < path.size() - 1 && parent != null; i++) {
				parent = parent.get(path.get(i)) instanceof CompoundTag child ? child : null;
			}
			if (parent != null) {
				parent.remove(path.getLast());
			}
		}
		return kept.hashCode();
	}
}
