package io.palmisano.aibuilder.core.blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.palmisano.aibuilder.core.blocks.BlockEntityData.Banner;
import io.palmisano.aibuilder.core.blocks.BlockEntityData.Book;
import io.palmisano.aibuilder.core.blocks.BlockEntityData.Container;
import io.palmisano.aibuilder.core.blocks.BlockEntityData.ItemFrame;
import io.palmisano.aibuilder.core.blocks.BlockEntityData.Sign;
import org.junit.jupiter.api.Test;

/** place_block_entity's data (spec §8): checked against the game's items, stored as plain text, trimmed. */
class BlockEntityDataTest {
	private static final ItemCatalog ITEMS = FakeItems.standard();

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	private static BlockEntityData parse(String args) {
		return BlockEntityData.parse(json(args), ITEMS);
	}

	/** Quotes, emoji and JSON-looking text stay as they are; formatting codes, line breaks and the overlong go (M5 Review Focus). */
	@Test
	void signTextIsPlainAndTrimmed() {
		String longLine = "x".repeat(500);
		Sign sign = (Sign) parse("{\"sign\":{\"front\":[\"  \\\"Welcome\\\" §ahome 🏠 \",\"{\\\"text\\\":\\\"json\\\"}\",\"" + longLine
				+ "\",\"line\\nbreak\"],\"color\":\"blue\",\"glowing\":true}}");
		assertEquals(List.of("\"Welcome\" home 🏠", "{\"text\":\"json\"}", "x".repeat(BlockEntityData.SIGN_LINE_MAX), "line break"), sign.front());
		assertEquals(List.of("", "", "", ""), sign.back());
		assertEquals("blue", sign.color());
		assertTrue(sign.glowing());
	}

	@Test
	void aSignHoldsFourLines() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> parse("{\"sign\":{\"front\":[\"1\",\"2\",\"3\",\"4\",\"5\"]}}"));
		assertTrue(e.getMessage().contains("4 lines"), e.getMessage());
	}

	@Test
	void signsAreBlackAndDullUnlessAsked() {
		Sign sign = (Sign) parse("{\"sign\":{\"back\":[\"Exit\"]}}");
		assertEquals("black", sign.color());
		assertFalse(sign.glowing());
		assertEquals("Exit", sign.back().getFirst());
	}

	@Test
	void unknownItemsAreRejected() {
		BlockEntityData.Refused e = assertThrows(BlockEntityData.Refused.class,
				() -> parse("{\"container\":{\"items\":[{\"item\":\"diamond\"},{\"item\":\"minecraft:unobtainium\",\"count\":3}]}}"));
		assertEquals(RejectReason.BAD_VALUE, e.reason());
		assertTrue(e.getMessage().contains("minecraft:unobtainium"), e.getMessage());
	}

	@Test
	void containersHoldStacksTheGameAllows() {
		Container chest = (Container) parse("{\"container\":{\"items\":[{\"item\":\"diamond\",\"count\":99},{\"item\":\"iron_sword\",\"count\":5},"
				+ "{\"item\":\"bread\"}]}}");
		assertEquals(List.of(new BlockEntityData.Stack("minecraft:diamond", 64), new BlockEntityData.Stack("minecraft:iron_sword", 1),
				new BlockEntityData.Stack("minecraft:bread", 1)), chest.items());
	}

	/** Nothing that holds items goes inside a container. */
	@Test
	void shulkerBoxesStayOut() {
		BlockEntityData.Refused e = assertThrows(BlockEntityData.Refused.class,
				() -> parse("{\"container\":{\"items\":[{\"item\":\"shulker_box\"}]}}"));
		assertTrue(e.getMessage().contains("holds items"), e.getMessage());
	}

	@Test
	void aContainerTakesTwentySevenStacks() {
		String stacks = String.join(",", java.util.Collections.nCopies(28, "{\"item\":\"bread\"}"));
		assertThrows(IllegalArgumentException.class, () -> parse("{\"container\":{\"items\":[" + stacks + "]}}"));
	}

	@Test
	void booksKeepTheirLineBreaksAndStayInBounds() {
		Book book = (Book) parse("{\"lectern\":{\"title\":\"" + "T".repeat(40) + "\",\"pages\":[\"Day one\\nIt rained.\",\"" + "p".repeat(900) + "\"]}}");
		assertEquals("T".repeat(BlockEntityData.TITLE_MAX), book.title());
		assertEquals("Claude", book.author());
		assertEquals(List.of("Day one\nIt rained.", "p".repeat(BlockEntityData.PAGE_MAX)), book.pages());
		String pages = String.join(",", java.util.Collections.nCopies(51, "\"page\""));
		assertThrows(IllegalArgumentException.class, () -> parse("{\"lectern\":{\"title\":\"t\",\"pages\":[" + pages + "]}}"));
	}

	@Test
	void bannersHaveKnownPatternsAndDyes() {
		Banner banner = (Banner) parse("{\"banner\":{\"patterns\":[{\"pattern\":\"stripe_bottom\",\"color\":\"red\"},{\"pattern\":\"minecraft:creeper\",\"color\":\"black\"}]}}");
		assertEquals(List.of(new BlockEntityData.Layer("minecraft:stripe_bottom", "red"), new BlockEntityData.Layer("minecraft:creeper", "black")),
				banner.layers());
		assertEquals(RejectReason.BAD_VALUE, assertThrows(BlockEntityData.Refused.class,
				() -> parse("{\"banner\":{\"patterns\":[{\"pattern\":\"dragon\",\"color\":\"red\"}]}}")).reason());
		assertEquals(RejectReason.BAD_VALUE, assertThrows(BlockEntityData.Refused.class,
				() -> parse("{\"banner\":{\"patterns\":[{\"pattern\":\"creeper\",\"color\":\"golden\"}]}}")).reason());
		String seven = String.join(",", java.util.Collections.nCopies(7, "{\"pattern\":\"creeper\",\"color\":\"red\"}"));
		assertThrows(IllegalArgumentException.class, () -> parse("{\"banner\":{\"patterns\":[" + seven + "]}}"));
	}

	/** An item frame faces a relative way, out of the block it hangs on; compass words are refused (spec §7). */
	@Test
	void itemFramesFaceRelativeWays() {
		ItemFrame frame = (ItemFrame) parse("{\"item_frame\":{\"facing\":\"right\",\"item\":\"diamond\",\"glowing\":true}}");
		assertEquals(new ItemFrame("west", "minecraft:diamond", true), frame);
		assertEquals(new ItemFrame("up", "", false), parse("{\"item_frame\":{\"facing\":\"up\"}}"));
		assertEquals(RejectReason.COMPASS_DIRECTION, assertThrows(BlockEntityData.Refused.class,
				() -> parse("{\"item_frame\":{\"facing\":\"north\"}}")).reason());
	}

	@Test
	void exactlyOneKindOfData() {
		assertThrows(IllegalArgumentException.class, () -> parse("{}"));
		assertThrows(IllegalArgumentException.class, () -> parse("{\"sign\":{},\"banner\":{\"patterns\":[]}}"));
	}
}
