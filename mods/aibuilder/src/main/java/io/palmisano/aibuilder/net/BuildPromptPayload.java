package io.palmisano.aibuilder.net;

import io.netty.buffer.ByteBuf;
import io.palmisano.aibuilder.AiBuilderMod;
import io.palmisano.aibuilder.core.request.SizeTier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: "build this". The server checks the prompt itself. The wire limit sits far above the prompt
 * limit, so an over-long prompt gets a chat answer instead of a decode failure that drops the connection.
 * {@code forceNew}: a new build here even next to the last one (the screen's mode toggle, spec §5.1).
 */
public record BuildPromptPayload(String prompt, SizeTier size, boolean forceNew) implements CustomPacketPayload {
	public static final int MAX_WIRE_CHARS = 8192;
	public static final Type<BuildPromptPayload> TYPE = new Type<>(AiBuilderMod.id("build_prompt"));

	/** By ordinal; an unknown number reads as {@code AUTO}. */
	static final StreamCodec<ByteBuf, SizeTier> SIZE_CODEC = ByteBufCodecs.VAR_INT.map(BuildPromptPayload::sizeAt, SizeTier::ordinal);

	public static final StreamCodec<RegistryFriendlyByteBuf, BuildPromptPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(MAX_WIRE_CHARS), BuildPromptPayload::prompt,
			SIZE_CODEC, BuildPromptPayload::size,
			ByteBufCodecs.BOOL, BuildPromptPayload::forceNew,
			BuildPromptPayload::new);

	public BuildPromptPayload(String prompt, SizeTier size) {
		this(prompt, size, false);
	}

	@Override
	public Type<BuildPromptPayload> type() {
		return TYPE;
	}

	private static SizeTier sizeAt(int ordinal) {
		SizeTier[] all = SizeTier.values();
		return ordinal >= 0 && ordinal < all.length ? all[ordinal] : SizeTier.AUTO;
	}
}
