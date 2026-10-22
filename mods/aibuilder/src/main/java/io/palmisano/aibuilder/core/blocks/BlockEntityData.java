package io.palmisano.aibuilder.core.blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.palmisano.aibuilder.core.frame.Directions;

/**
 * place_block_entity's data (spec §8), checked against the game's items and made plain: what a sign says, what a
 * container holds, a lectern's book, a banner's patterns, an item frame's item. Text is kept as plain text (never
 * read as JSON, formatting codes dropped) and trimmed to what the game takes.
 */
public sealed interface BlockEntityData {
	int SIGN_LINES = 4;
	/** The longest sign line the game takes from a client (about 15 characters show). */
	int SIGN_LINE_MAX = 384;
	int CONTAINER_STACKS = 27;
	int BOOK_PAGES = 50;
	int PAGE_MAX = 800;
	/** A written book's title and author, at most (vanilla's title limit). */
	int TITLE_MAX = 32;
	int BANNER_LAYERS = 6;
	List<String> DYES = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple",
			"blue", "brown", "green", "red", "black");
	List<String> FACINGS = List.of("forward", "back", "left", "right", "up", "down");

	/** The block entity this goes in; {@link BlockEntityKind#NONE} for an item frame, an entity of its own. */
	BlockEntityKind kind();

	/** Four lines a side; one color and glow for both. */
	record Sign(List<String> front, List<String> back, String color, boolean glowing) implements BlockEntityData {
		@Override
		public BlockEntityKind kind() {
			return BlockEntityKind.SIGN;
		}
	}

	record Stack(String item, int count) {
	}

	/** Slots in order from the first; a container with fewer slots takes what fits. */
	record Container(List<Stack> items) implements BlockEntityData {
		@Override
		public BlockEntityKind kind() {
			return BlockEntityKind.CONTAINER;
		}
	}

	/** A written book on a lectern; pages keep their line breaks. */
	record Book(String title, String author, List<String> pages) implements BlockEntityData {
		@Override
		public BlockEntityKind kind() {
			return BlockEntityKind.LECTERN;
		}
	}

	record Layer(String pattern, String color) {
	}

	record Banner(List<Layer> layers) implements BlockEntityData {
		@Override
		public BlockEntityKind kind() {
			return BlockEntityKind.BANNER;
		}
	}

	/**
	 * An item frame at a position, hanging on the block behind it. {@code facing}: the way it faces, out of that
	 * block, as a compass word of the template frame (up and down as they are); {@code item}: "" for an empty frame.
	 */
	record ItemFrame(String facing, String item, boolean glowing) implements BlockEntityData {
		@Override
		public BlockEntityKind kind() {
			return BlockEntityKind.NONE;
		}
	}

	/** A value the game doesn't have (an item, a pattern, a dye) or can't hold: Claude sees {@link #reason()}. */
	final class Refused extends RuntimeException {
		private final RejectReason reason;

		Refused(RejectReason reason, String message) {
			super(message);
			this.reason = reason;
		}

		public RejectReason reason() {
			return reason;
		}
	}

	/**
	 * Exactly one of {@code sign}, {@code container}, {@code lectern}, {@code banner} or {@code item_frame} from
	 * place_block_entity's arguments. Throws {@link IllegalArgumentException} for a malformed argument and
	 * {@link Refused} for a value the game doesn't have.
	 */
	static BlockEntityData parse(JsonObject args, ItemCatalog items) {
		List<String> given = new ArrayList<>();
		for (String kind : List.of("sign", "container", "lectern", "banner", "item_frame")) {
			if (args.has(kind) && !args.get(kind).isJsonNull()) {
				given.add(kind);
			}
		}
		if (given.size() != 1) {
			throw new IllegalArgumentException("give exactly one of sign, container, lectern, banner or item_frame"
					+ (given.isEmpty() ? "" : "; got " + String.join(", ", given)));
		}
		String kind = given.getFirst();
		JsonObject o = object(args, kind);
		return switch (kind) {
			case "sign" -> new Sign(lines(o, "front"), lines(o, "back"), dye(o, "color", "black"), bool(o, "glowing"));
			case "container" -> container(o, items);
			case "lectern" -> book(o);
			case "banner" -> banner(o, items);
			default -> frame(o, items);
		};
	}

	/**
	 * Plain text: formatting codes ({@code §} and the character after it) and control characters go, line breaks
	 * too unless {@code breaks} (they become spaces), ends trimmed, at most {@code max} characters (an emoji counts
	 * as one and is never cut in half).
	 */
	static String plain(String text, int max, boolean breaks) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length();) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == '§') {
				if (i < text.length()) {
					i += Character.charCount(text.codePointAt(i));
				}
			} else if (cp == '\n' && breaks) {
				out.append('\n');
			} else if (cp == '\r' && breaks) {
				// A Windows line break is the \n after it.
			} else {
				out.appendCodePoint(Character.isISOControl(cp) ? ' ' : cp);
			}
		}
		String trimmed = breaks ? out.toString().stripTrailing() : out.toString().strip();
		return trimmed.codePointCount(0, trimmed.length()) <= max ? trimmed : trimmed.substring(0, trimmed.offsetByCodePoints(0, max));
	}

	private static List<String> lines(JsonObject o, String side) {
		List<String> raw = strings(o, side);
		if (raw.size() > SIGN_LINES) {
			throw new IllegalArgumentException("a sign's " + side + " holds " + SIGN_LINES + " lines; got " + raw.size());
		}
		List<String> out = new ArrayList<>();
		raw.forEach(line -> out.add(plain(line, SIGN_LINE_MAX, false)));
		while (out.size() < SIGN_LINES) {
			out.add("");
		}
		return List.copyOf(out);
	}

	private static Container container(JsonObject o, ItemCatalog items) {
		JsonArray list = array(o, "items");
		if (list.size() > CONTAINER_STACKS) {
			throw new IllegalArgumentException("a container takes at most " + CONTAINER_STACKS + " stacks; got " + list.size());
		}
		List<Stack> stacks = new ArrayList<>();
		for (JsonElement e : list) {
			if (!e.isJsonObject()) {
				throw new IllegalArgumentException("container.items must be a list of {item, count}");
			}
			String item = item(e.getAsJsonObject(), items);
			if (items.holdsItems(item)) {
				throw new Refused(RejectReason.BAD_VALUE, item + " holds items of its own, so it can't go inside a container");
			}
			int count = e.getAsJsonObject().has("count") ? wholeNumber(e.getAsJsonObject(), "count") : 1;
			stacks.add(new Stack(item, Math.clamp(count, 1, items.maxStack(item))));
		}
		return new Container(List.copyOf(stacks));
	}

	private static Book book(JsonObject o) {
		List<String> pages = strings(o, "pages");
		if (pages.size() > BOOK_PAGES) {
			throw new IllegalArgumentException("a book takes at most " + BOOK_PAGES + " pages; got " + pages.size());
		}
		String title = plain(o.has("title") ? string(o, "title") : "", TITLE_MAX, false);
		String author = plain(o.has("author") ? string(o, "author") : "", TITLE_MAX, false);
		return new Book(title.isEmpty() ? "Untitled" : title, author.isEmpty() ? "Claude" : author,
				pages.stream().map(page -> plain(page, PAGE_MAX, true)).toList());
	}

	private static Banner banner(JsonObject o, ItemCatalog items) {
		JsonArray list = array(o, "patterns");
		if (list.size() > BANNER_LAYERS) {
			throw new IllegalArgumentException("a banner takes at most " + BANNER_LAYERS + " patterns; got " + list.size());
		}
		List<Layer> layers = new ArrayList<>();
		for (JsonElement e : list) {
			if (!e.isJsonObject()) {
				throw new IllegalArgumentException("banner.patterns must be a list of {pattern, color}");
			}
			String pattern = id(string(e.getAsJsonObject(), "pattern"));
			if (!items.isBannerPattern(pattern)) {
				throw new Refused(RejectReason.BAD_VALUE, "there's no banner pattern " + pattern
						+ " (stripe_bottom, border, cross, creeper, flower, skull, globe, bricks, gradient, rhombus, circle, half_horizontal…)");
			}
			layers.add(new Layer(pattern, dye(e.getAsJsonObject(), "color", null)));
		}
		return new Banner(List.copyOf(layers));
	}

	private static ItemFrame frame(JsonObject o, ItemCatalog items) {
		String facing = string(o, "facing").toLowerCase(Locale.ROOT);
		if (Directions.isCompassToken(facing)) {
			throw new Refused(RejectReason.COMPASS_DIRECTION, "facing takes forward, back, left, right, up or down, not " + facing);
		}
		if (!FACINGS.contains(facing)) {
			throw new Refused(RejectReason.BAD_VALUE, "facing takes forward, back, left, right, up or down; got " + facing);
		}
		String item = o.has("item") ? item(o, items) : "";
		return new ItemFrame(Directions.toTemplateToken(facing), item, bool(o, "glowing"));
	}

	private static String item(JsonObject o, ItemCatalog items) {
		String item = id(string(o, "item"));
		if (items.maxStack(item) == 0) {
			throw new Refused(RejectReason.BAD_VALUE, "there's no item " + item);
		}
		return item;
	}

	private static String dye(JsonObject o, String name, String fallback) {
		if (!o.has(name) && fallback != null) {
			return fallback;
		}
		String dye = string(o, name).toLowerCase(Locale.ROOT).replace("minecraft:", "");
		if (!DYES.contains(dye)) {
			throw new Refused(RejectReason.BAD_VALUE, name + " takes a dye color (" + String.join(", ", DYES) + "); got " + dye);
		}
		return dye;
	}

	private static String id(String raw) {
		String id = raw.trim().toLowerCase(Locale.ROOT);
		return id.contains(":") ? id : "minecraft:" + id;
	}

	private static JsonObject object(JsonObject o, String name) {
		JsonElement e = o.get(name);
		if (e == null || !e.isJsonObject()) {
			throw new IllegalArgumentException(name + " must be an object");
		}
		return e.getAsJsonObject();
	}

	private static JsonArray array(JsonObject o, String name) {
		JsonElement e = o.get(name);
		if (e == null || e.isJsonNull()) {
			return new JsonArray();
		}
		if (!e.isJsonArray()) {
			throw new IllegalArgumentException(name + " must be a list");
		}
		return e.getAsJsonArray();
	}

	private static List<String> strings(JsonObject o, String name) {
		List<String> out = new ArrayList<>();
		for (JsonElement e : array(o, name)) {
			if (!e.isJsonPrimitive()) {
				throw new IllegalArgumentException(name + " must be a list of strings");
			}
			out.add(e.getAsString());
		}
		return out;
	}

	private static String string(JsonObject o, String name) {
		JsonElement e = o.get(name);
		if (e == null || !e.isJsonPrimitive()) {
			throw new IllegalArgumentException(name + " must be a string");
		}
		return e.getAsString();
	}

	private static boolean bool(JsonObject o, String name) {
		JsonElement e = o.get(name);
		if (e == null || e.isJsonNull()) {
			return false;
		}
		if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
			throw new IllegalArgumentException(name + " must be true or false");
		}
		return e.getAsBoolean();
	}

	private static int wholeNumber(JsonObject o, String name) {
		JsonElement e = o.get(name);
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException(name + " must be a whole number");
		}
		double value = e.getAsDouble();
		if (value != Math.rint(value) || Math.abs(value) > 1_000_000) {
			throw new IllegalArgumentException(name + " must be a whole number");
		}
		return (int) value;
	}
}
