package io.palmisano.aibuilder.core.gadgets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.LocalBlock;
import org.junit.jupiter.api.Test;

/** The logic array's layout (M9 plan, design calls): what the redstone it's built from relies on. */
class PlaTest {
	private static final String[] SEGMENTS = {"abcdef", "bc", "abdeg", "abcdg", "bcfg", "acdfg", "acdefg", "abc", "abcdefg", "abcdfg"};

	/** A full adder cell as the adder uses it: inputs c, a, b; the sum out the back, the carry out the front. */
	static Gadget fullAdder() {
		Set<Integer> sum = new HashSet<>();
		Set<Integer> carry = new HashSet<>();
		for (int v = 0; v < 8; v++) {
			if (Integer.bitCount(v) % 2 == 1) {
				sum.add(v);
			}
			if (Integer.bitCount(v) >= 2) {
				carry.add(v);
			}
		}
		return Pla.build(List.of(sum, carry), Set.of(), new Pla.Layout("adder_cell", List.of("c", "a", "b"), List.of("s", "cout"), List.of(2, 10),
				Set.of(1)));
	}

	/** A BCD digit's seven segments, codes 10 to 15 don't care. */
	static Gadget digit() {
		List<Set<Integer>> on = new ArrayList<>();
		for (char segment : "abcdefg".toCharArray()) {
			Set<Integer> lit = new HashSet<>();
			for (int v = 0; v < 10; v++) {
				if (SEGMENTS[v].indexOf(segment) >= 0) {
					lit.add(v);
				}
			}
			on.add(lit);
		}
		Set<Integer> dc = new HashSet<>();
		for (int v = 10; v < 16; v++) {
			dc.add(v);
		}
		return Pla.build(on, dc, new Pla.Layout("digit_decoder", List.of("d0", "d1", "d2", "d3"), List.of("a", "b", "c", "d", "e", "f", "g"),
				List.of(0, 2, 4, 6, 8, 10, 12), Set.of()));
	}

	private static Map<Pos, LocalBlock> index(Gadget gadget) {
		Map<Pos, LocalBlock> at = new HashMap<>();
		for (LocalBlock block : gadget.blocks()) {
			assertFalse(at.containsKey(block.pos()), "two blocks at " + block.pos());
			at.put(block.pos(), block);
		}
		return at;
	}

	/** A torch strongly powers the block above it, which would power the dust beside it: air above every torch. */
	@Test
	void noTorchHasAConductorAboveIt() {
		for (Gadget gadget : List.of(fullAdder(), digit())) {
			Map<Pos, LocalBlock> at = index(gadget);
			int torches = 0;
			for (LocalBlock block : gadget.blocks()) {
				if (block.block().id().contains("torch")) {
					torches++;
					LocalBlock above = at.get(new Pos(block.pos().x(), block.pos().y() + 1, block.pos().z()));
					assertTrue(above == null || above.block().id().equals("air"), gadget.kind() + ": " + above + " over the torch at " + block.pos());
				}
			}
			assertTrue(torches > 0, gadget.kind() + " has no torches");
		}
	}

	/** Every port is outside the box, with a repeater one step in that takes the signal in or gives it out. */
	@Test
	void portsAreOutsideWithABufferInside() {
		Gadget adder = fullAdder();
		Map<Pos, LocalBlock> at = index(adder);
		assertEquals(List.of("c", "a", "b", "s", "cout"), adder.ports().stream().map(Port::name).toList());
		Pos size = adder.size();
		for (Port port : adder.ports()) {
			Pos p = port.at();
			boolean front = p.z() == -1;
			assertTrue(front || p.z() == size.z(), port + " isn't just outside the front or the back");
			assertTrue(p.x() >= 0 && p.x() < size.x() && p.y() >= 0 && p.y() < size.y(), port + " isn't against the gadget");
			LocalBlock buffer = at.get(new Pos(p.x(), p.y(), front ? 0 : size.z() - 1));
			assertNotNull(buffer, "no buffer inside " + port);
			assertEquals("repeater", buffer.block().id(), "inside " + port);
			// A repeater's facing names its input side: an input's takes from the port, an output's from inside.
			String takesFrom = buffer.block().state().get("facing");
			String toward = front ? "back" : "forward";
			assertEquals(port.input() ? toward : (front ? "forward" : "back"), takesFrom, "the buffer of " + port);
		}
		assertTrue(adder.ports().stream().anyMatch(p -> !p.input() && p.at().z() == -1), "the carry leaves through the front");
		assertTrue(adder.settleTicks() > 0, "no settle time");
	}
}
