package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.List;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import io.palmisano.aibuilder.AiBuilderMod;

/** Lists in the mod's saved data. */
final class SavedLists {
	private SavedLists() {
	}

	/**
	 * A list read one item at a time: an item that can't be read (a build naming a block the game no longer has, a
	 * mod's removed since) is left out with a warning saying what that costs, so it costs only itself. Read whole, it
	 * would fail the store, and the game would start an empty one and save it over the file.
	 */
	static <T> Codec<List<T>> skipping(Codec<T> item, String what, String loss) {
		Codec<List<T>> whole = item.listOf();
		return new Codec<>() {
			@Override
			public <O> DataResult<Pair<List<T>, O>> decode(DynamicOps<O> ops, O input) {
				return ops.getList(input).map(items -> {
					List<T> read = new ArrayList<>();
					items.accept(element -> {
						DataResult<T> one = item.parse(ops, element);
						one.result().ifPresentOrElse(read::add, () -> AiBuilderMod.LOG.warn("Left out a saved {} that can't be read ({}): {}", what, loss,
								one.error().map(DataResult.Error::message).orElse("")));
					});
					return Pair.of(read, ops.empty());
				});
			}

			@Override
			public <O> DataResult<O> encode(List<T> input, DynamicOps<O> ops, O prefix) {
				return whole.encode(input, ops, prefix);
			}
		};
	}
}
