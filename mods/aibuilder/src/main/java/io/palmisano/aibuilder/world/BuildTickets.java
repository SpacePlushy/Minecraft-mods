package io.palmisano.aibuilder.world;

import java.util.LinkedHashSet;
import java.util.Set;

import io.palmisano.aibuilder.AiBuilderMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/** Keeps a build's chunks loaded and ticking while it runs (spec §9). */
public final class BuildTickets {
	/** No timeout, and no persist flag: a ticket left behind is gone after a restart. */
	public static final TicketType TYPE = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);
	private static final int RADIUS = 1;

	private BuildTickets() {
	}

	public static void register() {
		Registry.register(BuiltInRegistries.TICKET_TYPE, AiBuilderMod.id("build"), TYPE);
	}

	/** Every chunk holding a block between the two corners, which may come in any order. */
	public static Set<ChunkPos> covering(BlockPos min, BlockPos max) {
		int x0 = SectionPos.blockToSectionCoord(Math.min(min.getX(), max.getX()));
		int x1 = SectionPos.blockToSectionCoord(Math.max(min.getX(), max.getX()));
		int z0 = SectionPos.blockToSectionCoord(Math.min(min.getZ(), max.getZ()));
		int z1 = SectionPos.blockToSectionCoord(Math.max(min.getZ(), max.getZ()));
		Set<ChunkPos> chunks = new LinkedHashSet<>();
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				chunks.add(new ChunkPos(x, z));
			}
		}
		return chunks;
	}

	/** Server thread. */
	public static void hold(ServerLevel level, Set<ChunkPos> chunks) {
		chunks.forEach(chunk -> level.getChunkSource().addTicketWithRadius(TYPE, chunk, RADIUS));
	}

	/** Server thread. */
	public static void release(ServerLevel level, Set<ChunkPos> chunks) {
		chunks.forEach(chunk -> level.getChunkSource().removeTicketWithRadius(TYPE, chunk, RADIUS));
	}
}
