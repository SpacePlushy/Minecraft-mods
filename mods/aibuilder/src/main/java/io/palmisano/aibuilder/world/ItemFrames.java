package io.palmisano.aibuilder.world;

import java.util.List;

import io.palmisano.aibuilder.core.place.UndoJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;

/** The item frames a build hangs (spec §10): the one kind of entity it spawns. Server thread. */
final class ItemFrames {
	private ItemFrames() {
	}

	/**
	 * Hangs a frame at {@code pos} facing out of the block behind it, holding {@code item} ("" for none); null when it
	 * can't hang there (nothing solid behind it, or another frame in the way).
	 */
	static ItemFrame hang(ServerLevel level, BlockPos pos, Direction facing, String item, boolean glowing) {
		ItemFrame frame = glowing ? new GlowItemFrame(level, pos, facing) : new ItemFrame(level, pos, facing);
		if (!frame.survives()) {
			return null;
		}
		if (!item.isEmpty()) {
			frame.setItem(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(item))), false);
		}
		return level.addFreshEntity(frame) ? frame : null;
	}

	/**
	 * Takes down, without dropping anything, the build's frames that still hold exactly what it put in them (or
	 * nothing: the player took it). A frame holding anything else, the same item renamed or enchanted included, holds
	 * the player's: it stays. Returns how many stayed.
	 */
	static int takeDown(ServerLevel level, List<UndoJournal.Spawned> spawned) {
		int stayed = 0;
		for (UndoJournal.Spawned s : spawned) {
			if (!s.kind().equals(UndoJournal.Spawned.FRAME)) {
				continue;
			}
			Entity entity = level.getEntity(s.id());
			if (!(entity instanceof ItemFrame frame) || frame.isRemoved()) {
				continue;
			}
			ItemStack held = frame.getItem();
			boolean ours = held.isEmpty() || !s.item().isEmpty()
					&& ItemStack.isSameItemSameComponents(held, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(s.item()))));
			if (ours) {
				frame.discard();
			} else {
				stayed++;
			}
		}
		return stayed;
	}
}
