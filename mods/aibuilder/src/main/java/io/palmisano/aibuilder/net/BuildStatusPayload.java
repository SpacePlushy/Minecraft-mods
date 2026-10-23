package io.palmisano.aibuilder.net;

import java.util.ArrayList;
import java.util.List;

import io.palmisano.aibuilder.AiBuilderMod;
import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.request.BuildStatus;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the HUD panel's state, once a second, only to the player whose build it is (spec §5.3);
 * {@link BuildStatus#CLEAR} takes it down. Strings and the phase list are bounded on the wire as well.
 */
public record BuildStatusPayload(BuildStatus status) implements CustomPacketPayload {
	public static final Type<BuildStatusPayload> TYPE = new Type<>(AiBuilderMod.id("build_status"));
	private static final int WIRE_CHARS = 256;

	public static final StreamCodec<RegistryFriendlyByteBuf, BuildStatusPayload> CODEC = StreamCodec.of(BuildStatusPayload::write,
			BuildStatusPayload::read);

	@Override
	public Type<BuildStatusPayload> type() {
		return TYPE;
	}

	private static void write(RegistryFriendlyByteBuf buf, BuildStatusPayload payload) {
		BuildStatus s = payload.status();
		buf.writeUtf(s.title(), WIRE_CHARS);
		buf.writeVarInt(s.phases().size());
		s.phases().forEach(phase -> buf.writeUtf(phase, WIRE_CHARS));
		buf.writeVarInt(s.phase());
		buf.writeVarInt(s.placed());
		buf.writeVarInt(s.callsUsed());
		buf.writeVarInt(s.callsMax());
		buf.writeUtf(s.note(), WIRE_CHARS);
	}

	private static BuildStatusPayload read(RegistryFriendlyByteBuf buf) {
		String title = buf.readUtf(WIRE_CHARS);
		int count = Math.min(buf.readVarInt(), Tunables.HUD_PHASES_MAX);
		List<String> phases = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			phases.add(buf.readUtf(WIRE_CHARS));
		}
		return new BuildStatusPayload(new BuildStatus(title, phases, buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
				buf.readUtf(WIRE_CHARS)));
	}
}
