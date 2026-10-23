package io.palmisano.aibuilder.core.gadgets;

import com.google.gson.JsonParser;
import io.palmisano.aibuilder.core.frame.Pos;

/**
 * A gadget a build placed (spec v2 §5.3), as its session keeps it: its kind and options (the JSON place_gadget was
 * given), where and which way; {@code build} is the session's build that placed it (undo of that build forgets it).
 * Everything else (its blocks, ports, spots) is worked out again from these.
 */
public record GadgetUse(String kind, String options, Pos at, String facing, int build) {
	/** Where its blocks, ports and spots are. */
	public GadgetPlacement.Placed placed() {
		return GadgetPlacement.place(Gadgets.make(kind, JsonParser.parseString(options).getAsJsonObject()), at, facing);
	}
}
