package io.palmisano.aibuilder.net;

import io.palmisano.aibuilder.AiBuilderMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client, the answer to {@link Query}: the title of the build a prompt from here would refine (spec §5.1's
 * mode line), or "" for a new build.
 */
public record BuildModePayload(String refining) implements CustomPacketPayload {
	public static final Type<BuildModePayload> TYPE = new Type<>(AiBuilderMod.id("build_mode"));
	public static final StreamCodec<RegistryFriendlyByteBuf, BuildModePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(256), BuildModePayload::refining, BuildModePayload::new);

	@Override
	public Type<BuildModePayload> type() {
		return TYPE;
	}

	/** Client to server, when the screen opens: what would a prompt from here do? */
	public record Query() implements CustomPacketPayload {
		public static final Type<Query> TYPE = new Type<>(AiBuilderMod.id("build_mode_query"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Query> CODEC = StreamCodec.unit(new Query());

		@Override
		public Type<Query> type() {
			return TYPE;
		}
	}
}
