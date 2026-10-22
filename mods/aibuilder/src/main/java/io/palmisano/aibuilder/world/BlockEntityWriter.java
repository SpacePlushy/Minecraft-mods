package io.palmisano.aibuilder.world;

import java.util.ArrayList;
import java.util.List;

import io.palmisano.aibuilder.core.blocks.BlockEntityData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.Container;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Gives a placed block entity what place_block_entity asked for (spec §8): a sign's text, a container's items, a
 * lectern's book, a banner's patterns. Text goes in as plain text ({@link Component#literal}), never parsed. Server
 * thread; the data was checked against the catalogs when the tool was called.
 */
final class BlockEntityWriter {
	private BlockEntityWriter() {
	}

	/** False when the block entity there isn't the kind the data is for (nothing is written). */
	static boolean write(ServerLevel level, BlockPos pos, BlockEntityData data) {
		BlockEntity entity = level.getBlockEntity(pos);
		switch (data) {
			case BlockEntityData.Sign sign when entity instanceof SignBlockEntity signEntity -> {
				signEntity.setText(text(sign.front(), sign), SignTextSlot.FRONT);
				signEntity.setText(text(sign.back(), sign), SignTextSlot.BACK);
			}
			case BlockEntityData.Container items when entity instanceof Container container -> {
				for (int i = 0; i < items.items().size() && i < container.getContainerSize(); i++) {
					BlockEntityData.Stack stack = items.items().get(i);
					container.setItem(i, new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(stack.item())), stack.count()));
				}
			}
			case BlockEntityData.Book book when entity instanceof LecternBlockEntity lectern -> {
				BlockState state = level.getBlockState(pos);
				if (state.hasProperty(LecternBlock.HAS_BOOK) && !state.getValue(LecternBlock.HAS_BOOK)) {
					// The same block in another state keeps its block entity.
					level.setBlock(pos, state.setValue(LecternBlock.HAS_BOOK, true), PlacementJob.FLAGS);
				}
				lectern.setBook(writtenBook(book));
			}
			case BlockEntityData.Banner banner when entity instanceof BannerBlockEntity bannerEntity -> {
				Registry<BannerPattern> patterns = level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
				List<BannerPatternLayers.Layer> layers = new ArrayList<>();
				for (BlockEntityData.Layer layer : banner.layers()) {
					layers.add(new BannerPatternLayers.Layer(patterns.getOrThrow(ResourceKey.create(Registries.BANNER_PATTERN, Identifier.parse(layer.pattern()))),
							DyeColor.byName(layer.color(), DyeColor.WHITE)));
				}
				bannerEntity.applyComponents(DataComponentMap.builder().set(DataComponents.BANNER_PATTERNS, new BannerPatternLayers(layers)).build(),
						DataComponentPatch.EMPTY);
			}
			default -> {
				return false;
			}
		}
		entity.setChanged();
		BlockState state = level.getBlockState(pos);
		level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
		return true;
	}

	private static SignText text(List<String> lines, BlockEntityData.Sign sign) {
		List<Component> messages = lines.stream().<Component>map(Component::literal).toList();
		return new SignText(messages, messages, DyeColor.byName(sign.color(), DyeColor.BLACK), sign.glowing());
	}

	private static ItemStack writtenBook(BlockEntityData.Book book) {
		ItemStack stack = new ItemStack(Items.WRITTEN_BOOK);
		List<Filterable<Component>> pages = book.pages().stream().map(page -> Filterable.<Component>passThrough(Component.literal(page))).toList();
		stack.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(Filterable.passThrough(book.title()), book.author(), 0, pages, true));
		return stack;
	}
}
