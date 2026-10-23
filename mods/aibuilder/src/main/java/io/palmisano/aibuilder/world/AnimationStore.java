package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.palmisano.aibuilder.AiBuilderMod;
import io.palmisano.aibuilder.core.anim.Motion;
import io.palmisano.aibuilder.core.anim.MovingPart;
import io.palmisano.aibuilder.core.anim.Vec3;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The world's moving builds (spec v2 §4.4), saved with the overworld's data: per build, where it is, the game time
 * its motion counts from, and its parts, each with its motion (none while it stands still) and its displays. A build
 * goes in once it has a part, grows as it's built, and comes out when undo takes its displays down. A saved build
 * that can't be read is left out with a warning, so it costs only itself. Server thread.
 */
public final class AnimationStore extends SavedData {
	/** Which build, where, and the game time its motion counts from. */
	public record Key(UUID id, ResourceKey<Level> dimension, LocalFrame frame, long start) {
	}

	/** One display of a part: its entity, and the block it shows, local to the build's frame. */
	public record PartDisplay(UUID id, Pos block) {
	}

	/** A part: its motion (no motions while it stands still) and its displays, in the order they were made. */
	public record Part(MovingPart motion, List<PartDisplay> displays) {
		public Part {
			displays = List.copyOf(displays);
		}
	}

	/** A moving build as the world saves it. */
	public record MovingBuild(Key key, List<Part> parts) {
		public MovingBuild {
			parts = List.copyOf(parts);
		}

		public Optional<Part> part(String name) {
			return parts.stream().filter(part -> part.motion().name().equals(name)).findFirst();
		}

		/** Each part's motion by name, as {@code Poses} reads them. */
		public Map<String, MovingPart> motions() {
			Map<String, MovingPart> out = new HashMap<>();
			parts.forEach(part -> out.put(part.motion().name(), part.motion()));
			return out;
		}
	}

	/** A build as it's kept, its parts growing while it's built; the engine reads these. */
	static final class Live {
		final Key key;
		final Map<String, LivePart> parts = new LinkedHashMap<>();
		private Map<String, MovingPart> motions;

		Live(Key key) {
			this.key = key;
		}

		Map<String, MovingPart> motions() {
			if (motions == null) {
				motions = new HashMap<>();
				parts.forEach((name, part) -> motions.put(name, part.motion));
			}
			return motions;
		}

		LivePart part(String name) {
			return parts.computeIfAbsent(name, n -> new LivePart(new MovingPart(n, Vec3.ZERO, List.of(), "")));
		}
	}

	static final class LivePart {
		MovingPart motion;
		final List<PartDisplay> displays = new ArrayList<>();
		/**
		 * Of a part that stands still, the displays the engine has yet to put back at rest; null for all of them, as once
		 * loaded or given back its stillness. Server thread.
		 */
		Set<UUID> unrested;

		LivePart(MovingPart motion) {
			this.motion = motion;
		}

		boolean standsStill() {
			return motion.motions().isEmpty() && motion.parent().isEmpty();
		}
	}

	private static final Codec<Vec3> PIVOT = Codec.DOUBLE.listOf().comapFlatMap(
			xyz -> xyz.size() == 3 ? DataResult.success(new Vec3(xyz.get(0), xyz.get(1), xyz.get(2))) : DataResult.error(() -> "a pivot is 3 numbers"),
			v -> List.of(v.x(), v.y(), v.z()));

	/** A motion as animate reads it, so a saved one is held to the same limits. */
	private static final Codec<Motion> MOTION = Codec.STRING.comapFlatMap(json -> {
		try {
			return DataResult.success(Motion.parse(JsonParser.parseString(json).getAsJsonObject()));
		} catch (JsonParseException | IllegalArgumentException | IllegalStateException e) {
			return DataResult.error(() -> "a saved motion can't be read: " + e.getMessage());
		}
	}, motion -> motion.toJson().toString());

	private static final Codec<Facing> FACING = Codec.STRING.comapFlatMap(name -> {
		for (Facing facing : Facing.values()) {
			if (facing.name().toLowerCase(Locale.ROOT).equals(name)) {
				return DataResult.success(facing);
			}
		}
		return DataResult.error(() -> "not a facing: " + name);
	}, facing -> facing.name().toLowerCase(Locale.ROOT));

	private static final Codec<long[]> LONGS = Codec.LONG_STREAM.xmap(LongStream::toArray, LongStream::of);

	private record SavedMotion(String name, Vec3 pivot, List<Motion> motions, String parent) {
		static final Codec<SavedMotion> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.STRING.fieldOf("name").forGetter(SavedMotion::name),
				PIVOT.fieldOf("pivot").forGetter(SavedMotion::pivot),
				MOTION.listOf().fieldOf("motions").forGetter(SavedMotion::motions),
				Codec.STRING.optionalFieldOf("parent", "").forGetter(SavedMotion::parent)).apply(i, SavedMotion::new));

		DataResult<MovingPart> part() {
			try {
				return DataResult.success(new MovingPart(name, pivot, motions, parent));
			} catch (IllegalArgumentException e) {
				return DataResult.error(() -> "a saved part can't be read: " + e.getMessage());
			}
		}
	}

	/** A moving part's name, pivot, motions and parent, held to animate's limits. */
	static final Codec<MovingPart> MOVING_PART = SavedMotion.CODEC.flatXmap(SavedMotion::part,
			m -> DataResult.success(new SavedMotion(m.name(), m.pivot(), m.motions(), m.parent())));

	/** A part as saved: its displays as two arrays, the ids two longs each and the blocks packed. */
	private record SavedPart(MovingPart motion, long[] ids, long[] blocks) {
		static final Codec<SavedPart> CODEC = RecordCodecBuilder.create(i -> i.group(
				MOVING_PART.fieldOf("motion").forGetter(SavedPart::motion),
				LONGS.fieldOf("display_ids").forGetter(SavedPart::ids),
				LONGS.fieldOf("display_blocks").forGetter(SavedPart::blocks)).apply(i, SavedPart::new));

		static SavedPart of(Part part) {
			int n = part.displays().size();
			long[] ids = new long[2 * n];
			long[] blocks = new long[n];
			for (int k = 0; k < n; k++) {
				PartDisplay d = part.displays().get(k);
				ids[2 * k] = d.id().getMostSignificantBits();
				ids[2 * k + 1] = d.id().getLeastSignificantBits();
				blocks[k] = BlockPos.asLong(d.block().x(), d.block().y(), d.block().z());
			}
			return new SavedPart(part.motion(), ids, blocks);
		}

		DataResult<Part> part() {
			if (ids.length != 2 * blocks.length) {
				return DataResult.error(() -> "a saved part's displays don't add up");
			}
			List<PartDisplay> displays = new ArrayList<>(blocks.length);
			for (int k = 0; k < blocks.length; k++) {
				BlockPos block = BlockPos.of(blocks[k]);
				displays.add(new PartDisplay(new UUID(ids[2 * k], ids[2 * k + 1]), new Pos(block.getX(), block.getY(), block.getZ())));
			}
			return DataResult.success(new Part(motion, displays));
		}
	}

	private static final Codec<Part> PART = SavedPart.CODEC.flatXmap(SavedPart::part, part -> DataResult.success(SavedPart.of(part)));

	private static final Codec<MovingBuild> BUILD = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.CODEC.fieldOf("id").forGetter(b -> b.key().id()),
			Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(b -> b.key().dimension()),
			Codec.LONG.fieldOf("origin").forGetter(b -> {
				Pos o = b.key().frame().origin();
				return BlockPos.asLong(o.x(), o.y(), o.z());
			}),
			FACING.fieldOf("facing").forGetter(b -> b.key().frame().facing()),
			Codec.LONG.fieldOf("start").forGetter(b -> b.key().start()),
			PART.listOf().fieldOf("parts").forGetter(MovingBuild::parts)).apply(i, (id, dimension, origin, facing, start, parts) -> {
				BlockPos at = BlockPos.of(origin);
				return new MovingBuild(new Key(id, dimension, new LocalFrame(new Pos(at.getX(), at.getY(), at.getZ()), facing), start), parts);
			}));

	public static final Codec<AnimationStore> CODEC = SavedLists.skipping(BUILD, "moving build", "it stays where it is").fieldOf("builds").codec()
			.xmap(AnimationStore::of, AnimationStore::builds);

	public static final SavedDataType<AnimationStore> TYPE = new SavedDataType<>(AiBuilderMod.id("animations"), AnimationStore::new, CODEC, null);

	private final Map<UUID, Live> builds = new LinkedHashMap<>();

	public AnimationStore() {
	}

	/** Kept with the overworld's data, whichever dimension a build is in. */
	public static AnimationStore get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	/** A display the build made for a part; the build and the part go in with their first. */
	public void display(Key key, String part, PartDisplay display) {
		live(key).part(part).displays.add(display);
		setDirty();
	}

	/** A part's motion, as animate gave it, replacing any it had. */
	public void animate(Key key, MovingPart motion) {
		Live build = live(key);
		build.part(motion.name()).motion = motion;
		build.motions = null;
		setDirty();
	}

	/** Forgets these displays (undo took them down, or they're gone); a part left with none goes, and a build with no parts. */
	public void forget(Set<UUID> displays) {
		boolean changed = false;
		for (Iterator<Live> builds = this.builds.values().iterator(); builds.hasNext();) {
			Live build = builds.next();
			for (Iterator<LivePart> parts = build.parts.values().iterator(); parts.hasNext();) {
				LivePart part = parts.next();
				if (part.displays.removeIf(d -> displays.contains(d.id()))) {
					changed = true;
					if (part.displays.isEmpty()) {
						parts.remove();
						build.motions = null;
					}
				}
			}
			if (build.parts.isEmpty()) {
				builds.remove();
			}
		}
		if (changed) {
			setDirty();
		}
	}

	/** Gives a part back the motion it had (undoing a refinement), while the build still has the part. */
	public void restore(UUID build, MovingPart motion) {
		Live live = builds.get(build);
		if (live != null && live.parts.containsKey(motion.name())) {
			LivePart part = live.parts.get(motion.name());
			part.motion = motion;
			// Given back its stillness, the part is put back at rest from wherever its motion had got it to.
			part.unrested = null;
			live.motions = null;
			setDirty();
		}
	}

	/** Whether the moving build is still the store's. */
	public boolean has(UUID build) {
		return builds.containsKey(build);
	}

	/** A part's motion as it stands, if the build has the part. */
	public Optional<MovingPart> motion(UUID build, String part) {
		Live live = builds.get(build);
		return live == null || !live.parts.containsKey(part) ? Optional.empty() : Optional.of(live.parts.get(part).motion);
	}

	/** One moving build as it stands. */
	public Optional<MovingBuild> build(UUID id) {
		return builds().stream().filter(build -> build.key().id().equals(id)).findFirst();
	}

	/** Every moving build as it stands, in the order they began. */
	public List<MovingBuild> builds() {
		List<MovingBuild> out = new ArrayList<>(builds.size());
		for (Live build : builds.values()) {
			List<Part> parts = new ArrayList<>(build.parts.size());
			build.parts.values().forEach(part -> parts.add(new Part(part.motion, part.displays)));
			out.add(new MovingBuild(build.key, parts));
		}
		return out;
	}

	/** The builds the engine walks, as they're kept. */
	Collection<Live> live() {
		return builds.values();
	}

	/** Whether {@code build} is still one of the store's (undo hasn't forgotten it). */
	boolean holds(Live build) {
		return builds.get(build.key.id()) == build;
	}

	private Live live(Key key) {
		return builds.computeIfAbsent(key.id(), id -> new Live(key));
	}

	private static AnimationStore of(List<MovingBuild> saved) {
		AnimationStore store = new AnimationStore();
		for (MovingBuild build : saved) {
			Live live = store.live(build.key());
			for (Part part : build.parts()) {
				LivePart kept = live.part(part.motion().name());
				kept.motion = part.motion();
				kept.displays.addAll(part.displays());
			}
		}
		return store;
	}
}
