package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.palmisano.aibuilder.AiBuilderMod;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.request.BuildSession;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Each player's refinable build (spec §5.4), saved with the world, so "add a moat" works after a restart too. */
public final class SessionStore extends SavedData {
	private static final Codec<Box> BOX = Codec.INT_STREAM.comapFlatMap(stream -> {
		int[] b = stream.toArray();
		return b.length == 6 ? com.mojang.serialization.DataResult.success(Box.of(b[0], b[1], b[2], b[3], b[4], b[5]))
				: com.mojang.serialization.DataResult.error(() -> "a box is 6 numbers");
	}, box -> IntStream.of(box.min().x(), box.min().y(), box.min().z(), box.max().x(), box.max().y(), box.max().z()));

	private static final Codec<Pos> POS = Codec.LONG.xmap(packed -> {
		BlockPos p = BlockPos.of(packed);
		return new Pos(p.getX(), p.getY(), p.getZ());
	}, pos -> BlockPos.asLong(pos.x(), pos.y(), pos.z()));

	private static final Codec<BuildSession> SESSION = RecordCodecBuilder.create(i -> i.group(
			Codec.STRING.fieldOf("dimension").forGetter(BuildSession::dimension),
			Codec.STRING.fieldOf("title").forGetter(BuildSession::title),
			POS.fieldOf("origin").forGetter(BuildSession::origin),
			Codec.STRING.xmap(Facing::valueOf, Facing::name).fieldOf("facing").forGetter(BuildSession::facing),
			Codec.STRING.xmap(SizeTier::valueOf, SizeTier::name).fieldOf("size").forGetter(BuildSession::size),
			BOX.fieldOf("changed").forGetter(BuildSession::changed),
			Codec.STRING.fieldOf("claude_session").forGetter(BuildSession::claudeSession),
			Codec.STRING.listOf().fieldOf("prompts").forGetter(BuildSession::prompts),
			Codec.INT.fieldOf("builds").forGetter(BuildSession::builds),
			Codec.BOOL.fieldOf("last_undone").forGetter(BuildSession::lastUndone),
			// Saved before moving parts: none.
			Codec.STRING.optionalFieldOf("moving_build", "").forGetter(BuildSession::movingBuild)).apply(i, BuildSession::new));

	private record Entry(UUID player, BuildSession session) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
				UUIDUtil.CODEC.fieldOf("player").forGetter(Entry::player),
				SESSION.fieldOf("session").forGetter(Entry::session)).apply(i, Entry::new));
	}

	public static final Codec<SessionStore> CODEC = Entry.CODEC.listOf().fieldOf("sessions").codec().xmap(SessionStore::fromEntries,
			SessionStore::toEntries);

	public static final SavedDataType<SessionStore> TYPE = new SavedDataType<>(AiBuilderMod.id("sessions"), SessionStore::new, CODEC, null);

	private final Map<UUID, BuildSession> sessions = new HashMap<>();

	public SessionStore() {
	}

	/** Kept with the overworld's data, whichever dimension the build is in. */
	public static SessionStore get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(TYPE);
	}

	public Optional<BuildSession> get(UUID player) {
		return Optional.ofNullable(sessions.get(player));
	}

	public void put(UUID player, BuildSession session) {
		sessions.put(player, session);
		setDirty();
	}

	public void remove(UUID player) {
		if (sessions.remove(player) != null) {
			setDirty();
		}
	}

	private static SessionStore fromEntries(List<Entry> entries) {
		SessionStore store = new SessionStore();
		entries.forEach(e -> store.sessions.put(e.player(), e.session()));
		return store;
	}

	private List<Entry> toEntries() {
		List<Entry> entries = new ArrayList<>();
		sessions.forEach((player, session) -> entries.add(new Entry(player, session)));
		return entries;
	}
}
