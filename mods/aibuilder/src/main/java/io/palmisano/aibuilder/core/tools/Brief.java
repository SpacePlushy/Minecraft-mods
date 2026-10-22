package io.palmisano.aibuilder.core.tools;

import java.util.List;
import java.util.Map;

/** Claude's design brief. {@code palette} maps role → block id, in the order Claude gave it. */
public record Brief(String title, String style, int width, int height, int depth, Map<String, String> palette, List<String> phases) {
}
