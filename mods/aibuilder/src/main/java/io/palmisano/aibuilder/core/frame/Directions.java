package io.palmisano.aibuilder.core.frame;

import java.util.Arrays;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Relative direction words (what Claude writes) against compass words as if the player faced south (what the
 * game's block states use before rotation): forward = south, back = north, right = west, left = east. That frame
 * is a real, unmirrored Minecraft frame, so the game's own rotation turns it to the real facing.
 */
public final class Directions {
	private static final Map<String, String> TO_TEMPLATE = Map.of("forward", "south", "back", "north", "right", "west", "left", "east");
	private static final Map<String, String> TO_LOCAL = Map.of("south", "forward", "north", "back", "west", "right", "east", "left");

	private Directions() {
	}

	public static String toTemplateToken(String token) {
		return TO_TEMPLATE.getOrDefault(token, token);
	}

	public static String toLocalToken(String token) {
		return TO_LOCAL.getOrDefault(token, token);
	}

	/** Translates each {@code _}-separated token, so a rail's "back_forward" becomes "north_south". */
	public static String toTemplateValue(String value) {
		return mapTokens(value, Directions::toTemplateToken);
	}

	public static String toLocalValue(String value) {
		return mapTokens(value, Directions::toLocalToken);
	}

	public static boolean isCompassToken(String token) {
		return TO_LOCAL.containsKey(token);
	}

	public static boolean mentionsCompass(String nameOrValue) {
		return Arrays.stream(nameOrValue.split("_")).anyMatch(Directions::isCompassToken);
	}

	private static String mapTokens(String value, UnaryOperator<String> map) {
		return Arrays.stream(value.split("_", -1)).map(map).collect(Collectors.joining("_"));
	}
}
