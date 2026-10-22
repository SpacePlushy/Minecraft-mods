package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.palmisano.aibuilder.core.Tunables;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.FireMask;
import io.palmisano.aibuilder.core.place.ProtectionMask;
import io.palmisano.aibuilder.core.place.RestoreDecision;
import io.palmisano.aibuilder.core.place.UndoJournal;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.site.Cell;
import io.palmisano.aibuilder.core.site.ColumnProbe;
import io.palmisano.aibuilder.core.site.SurveyFloor;
import io.palmisano.aibuilder.core.survey.Column;
import io.palmisano.aibuilder.core.survey.TerrainSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Reads a local area of the world into a {@link TerrainSnapshot} on the server tick, a time budget at a time (spec
 * §11), and, when asked, the {@link ProtectionMask} of the allowed box (every block that isn't natural, isn't air and
 * isn't what one of the player's earlier builds left there) and the {@link FireMask} in and around it (lava, and what
 * lava sets alight). Loads the area's chunks first and lets them go after.
 */
public final class WorldSurvey implements Tickable {
	public record Survey(TerrainSnapshot terrain, ProtectionMask protection, FireMask fire) {
	}

	private final ServerLevel level;
	private final LocalFrame frame;
	private final Box allowed;
	private final int minX;
	private final int minZ;
	private final int width;
	private final int depth;
	private final boolean protect;
	private final List<UndoJournal<BlockState, CompoundTag>> earlier;
	private final Set<ChunkPos> chunks;
	private final CompletableFuture<Void> loaded;
	private final ProtectionMask mask;
	private final FireMask fire;
	private final List<Column> columns = new ArrayList<>();
	private final CompletableFuture<Survey> result = new CompletableFuture<>();
	private int next;

	/** Server thread. */
	public WorldSurvey(ServerLevel level, LocalFrame frame, Box allowed, int minX, int minZ, int width, int depth, boolean protect,
			List<UndoJournal<BlockState, CompoundTag>> earlier) {
		this.level = level;
		this.frame = frame;
		this.allowed = allowed;
		this.minX = minX;
		this.minZ = minZ;
		this.width = width;
		this.depth = depth;
		this.protect = protect;
		this.earlier = List.copyOf(earlier);
		this.chunks = BuildTickets.covering(Positions.toBlockPos(frame.toWorld(new Pos(minX, 0, minZ))),
				Positions.toBlockPos(frame.toWorld(new Pos(minX + width - 1, 0, minZ + depth - 1))));
		this.loaded = BuildTickets.load(level, chunks);
		this.mask = protect ? new ProtectionMask(allowed) : ProtectionMask.none();
		this.fire = protect ? FireMask.around(allowed) : FireMask.none();
	}

	/** Completes on the server thread once every column is read. */
	public CompletableFuture<Survey> result() {
		return result;
	}

	@Override
	public boolean tick() {
		if (result.isDone()) {
			return true;
		}
		if (!loaded.isDone()) {
			return false;
		}
		long deadline = System.nanoTime() + Tunables.TICK_BUDGET_NANOS;
		while (next < width * depth && System.nanoTime() < deadline) {
			columns.add(column(minX + next % width, minZ + next / width));
			next++;
		}
		if (next < width * depth) {
			return false;
		}
		BuildTickets.unload(level, chunks);
		result.complete(new Survey(new TerrainSnapshot(minX, minZ, width, depth, columns), mask, fire));
		return true;
	}

	@Override
	public void failed(RuntimeException error) {
		BuildTickets.unload(level, chunks);
		result.completeExceptionally(error);
	}

	private Column column(int x, int z) {
		int originY = frame.origin().y();
		Pos world = frame.toWorld(new Pos(x, 0, z));
		int wx = world.x();
		int wz = world.z();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		ColumnProbe probe = Sites.column(level, wx, wz);

		// Protection runs through the whole allowed box, so a cellar or a cave base counts too; the fire mask a little
		// beyond it, as far as lava reaches.
		int protectedCount = 0;
		Map<String, Integer> kinds = new HashMap<>();
		Box fireBox = fire.box();
		if (protect && x >= fireBox.min().x() && x <= fireBox.max().x() && z >= fireBox.min().z() && z <= fireBox.max().z()) {
			for (int y = fireBox.min().y(); y <= fireBox.max().y(); y++) {
				BlockState state = level.getBlockState(pos.set(wx, originY + y, wz));
				if (state.isAir()) {
					continue;
				}
				Pos local = new Pos(x, y, z);
				if (state.getFluidState().is(FluidTags.LAVA)) {
					fire.lava(local);
				} else if (state.ignitedByLava()) {
					fire.flammable(local);
				}
				if (!allowed.contains(local)) {
					continue;
				}
				if (ProtectionHeuristic.isUnbreakable(state)) {
					mask.unbreakable(local);
				} else if (!ProtectionHeuristic.isNatural(state) && !leftByAnEarlierBuild(pos, state)) {
					mask.protect(local);
					protectedCount++;
					kinds.merge(id(state), 1, Integer::sum);
				}
			}
		}
		String mainKind = kinds.entrySet().stream()
				.max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
				.map(Map.Entry::getKey).orElse("");

		// From just above the origin's ground, like the origin's own floor, but to a mountain's top (SurveyFloor).
		OptionalInt found = SurveyFloor.find(probe, originY + 1, originY + allowed.max().y());
		if (found.isEmpty()) {
			return new Column(Column.NONE, Column.NONE, Column.NONE, "minecraft:air", 0, 0, false, protectedCount, mainKind);
		}
		int floor = found.getAsInt();
		BlockState floorState = level.getBlockState(pos.set(wx, floor, wz));
		int water = 0;
		int lava = 0;
		if (!floorState.getFluidState().isEmpty()) {
			int fluidDepth = 0;
			while (fluidDepth < 64 && !level.getBlockState(pos.set(wx, floor - fluidDepth, wz)).getFluidState().isEmpty()) {
				fluidDepth++;
			}
			if (floorState.getFluidState().is(FluidTags.LAVA)) {
				lava = fluidDepth;
			} else {
				water = fluidDepth;
			}
		}

		// The highest block that isn't air, at most the box's top; between it and the floor, trees and any ceiling.
		int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1, originY + allowed.max().y());
		while (top > floor && level.getBlockState(pos.set(wx, top, wz)).isAir()) {
			top--;
		}
		int ceiling = Column.NONE;
		boolean tree = false;
		for (int y = floor + 1; y <= top; y++) {
			BlockState state = level.getBlockState(pos.set(wx, y, wz));
			if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
				tree = true;
			} else if (ceiling == Column.NONE && probe.at(y) == Cell.GROUND) {
				ceiling = y - originY;
			}
		}

		return new Column(floor - originY, top - originY, ceiling, id(floorState), water, lava, tree, protectedCount, mainKind);
	}

	/**
	 * Still exactly what one of the player's earlier builds left there, its data too: theirs to change (spec §11). A
	 * chest that build placed and the player has since filled is the player's now, as undo would judge it.
	 */
	private boolean leftByAnEarlierBuild(BlockPos at, BlockState state) {
		Pos world = Positions.toPos(at);
		for (UndoJournal<BlockState, CompoundTag> journal : earlier) {
			if (journal.has(world) && journal.decide(world, id(state), BlockEntityHash.of(level.getBlockEntity(at), level.registryAccess()))
					== RestoreDecision.RESTORE) {
				return true;
			}
		}
		return false;
	}

	private static String id(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}
}
