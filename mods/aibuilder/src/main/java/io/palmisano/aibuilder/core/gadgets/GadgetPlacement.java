package io.palmisano.aibuilder.core.gadgets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.palmisano.aibuilder.core.blocks.BlockSpec;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.shapes.LocalBlock;

/**
 * A gadget put in the build's frame (spec v2 §5.3): its front-left-bottom block at {@code at}, its forward turned to
 * {@code facing} (forward, right, back or left, any case), the direction words in its blocks' states and its ports
 * turning with it. Pure.
 */
public final class GadgetPlacement {
	/** The gadget's blocks, ports and named spots in the build's frame, and the box it fills. */
	public record Placed(List<LocalBlock> blocks, List<Port> ports, Map<String, Pos> marks, Box box) {
	}

	/** Relative directions round the compass, clockwise seen from above: a turn moves each word on by its own steps. */
	private static final List<String> AROUND = List.of("forward", "right", "back", "left");

	private GadgetPlacement() {
	}

	public static Placed place(Gadget gadget, Pos at, String facing) {
		int turn = AROUND.indexOf(facing.toLowerCase(Locale.ROOT));
		if (turn < 0) {
			throw new IllegalArgumentException("facing must be forward, right, back or left, not \"" + facing + "\"");
		}
		List<LocalBlock> blocks = new ArrayList<>();
		for (LocalBlock block : gadget.blocks()) {
			blocks.add(new LocalBlock(move(at, block.pos(), turn), turned(block.block(), turn), block.onlyReplacing(), block.data()));
		}
		List<Port> ports = new ArrayList<>();
		for (Port port : gadget.ports()) {
			ports.add(new Port(port.name(), port.input(), move(at, port.at(), turn), port.what()));
		}
		Map<String, Pos> marks = new LinkedHashMap<>();
		gadget.marks().forEach((name, p) -> marks.put(name, move(at, p, turn)));
		Pos size = gadget.size();
		Pos a = move(at, new Pos(0, 0, 0), turn);
		Pos b = move(at, new Pos(Math.max(0, size.x() - 1), Math.max(0, size.y() - 1), Math.max(0, size.z() - 1)), turn);
		return new Placed(blocks, ports, marks, Box.of(a.x(), a.y(), a.z(), b.x(), b.y(), b.z()));
	}

	/** Where the gadget's {@code p} lands: its forward goes the way it faces, its right a quarter turn clockwise from that. */
	private static Pos move(Pos at, Pos p, int turn) {
		return switch (turn) {
			case 0 -> new Pos(at.x() + p.x(), at.y() + p.y(), at.z() + p.z());
			case 1 -> new Pos(at.x() + p.z(), at.y() + p.y(), at.z() - p.x());
			case 2 -> new Pos(at.x() - p.x(), at.y() + p.y(), at.z() - p.z());
			default -> new Pos(at.x() - p.z(), at.y() + p.y(), at.z() + p.x());
		};
	}

	/** The block's state with its direction words turned (up and down stay), and a quarter turn swapping axis x and z. */
	private static BlockSpec turned(BlockSpec spec, int turn) {
		if (turn == 0 || spec.state().isEmpty()) {
			return spec;
		}
		Map<String, String> state = new LinkedHashMap<>();
		spec.state().forEach((name, value) -> {
			int i = AROUND.indexOf(value);
			if (name.equals("axis") && turn % 2 == 1) {
				state.put(name, value.equals("x") ? "z" : value.equals("z") ? "x" : value);
			} else {
				state.put(name, i < 0 ? value : AROUND.get((i + turn) % 4));
			}
		});
		return new BlockSpec(spec.id(), state);
	}
}
