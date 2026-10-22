package io.palmisano.aibuilder.core.tools;

import io.palmisano.aibuilder.core.frame.Pos;

/**
 * What {@code get_context} says about the world: ids, a time of day and weather in words, the player's local position
 * (null when they're offline or in another dimension).
 */
public record WorldFacts(String dimension, String biome, String time, String weather, String groundBlock, Pos player) {
}
