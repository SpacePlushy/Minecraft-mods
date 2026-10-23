package io.palmisano.aibuilder.core.gadgets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
				List.of(2, 4, 6, 8, 10, 12, 14), Set.of()));
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

	/**
	 * A tap gives power only while its product holds, so an output line carries its first tap's signal alone to the exit:
	 * walking from there, the dust between repeaters never runs out.
	 */
	@Test
	void anOutputsFirstTapAloneReachesItsExit() {
		Gadget decoder = digit();
		Map<Pos, LocalBlock> at = index(decoder);
		for (Port port : decoder.ports()) {
			if (port.input()) {
				continue;
			}
			int x = port.at().x();
			int start = decoder.size().z() - 1;
			while (at.containsKey(new Pos(x, 1, start - 1))) {
				start--;
			}
			int strength = 15;
			for (int z = start; z < decoder.size().z() - 1; z++) {
				LocalBlock spot = at.get(new Pos(x, 1, z));
				assertTrue(strength >= 1, port.name() + "'s line has run out at gz " + z);
				strength = spot.block().id().equals("repeater") ? 15 : strength - 1;
			}
			assertTrue(strength >= 1, port.name() + "'s line reaches its buffer at " + strength);
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

	/**
	 * A product row's dust is lit by its AND taps' torches and must still be lit at each OR tap: 15 blocks along it is
	 * too far (M9 Task 6: a number's arrays lit segments they shouldn't). Every array keeps its taps within 14.
	 */
	@Test
	void everyOrTapIsWithinReachOfEveryAndTap() {
		for (Gadget gadget : List.of(fullAdder(), digit())) {
			Map<Integer, List<Integer>> and = new HashMap<>();
			Map<Integer, List<Integer>> or = new HashMap<>();
			for (LocalBlock block : gadget.blocks()) {
				if (block.block().id().equals("redstone_wall_torch") && block.pos().y() == 4 && block.pos().z() >= Pla.C0) {
					and.computeIfAbsent(block.pos().z(), z -> new ArrayList<>()).add(block.pos().x());
				}
				if (block.block().id().equals("redstone_wall_torch") && block.pos().y() == 2) {
					or.computeIfAbsent(block.pos().z() - 1, z -> new ArrayList<>()).add(block.pos().x());
				}
			}
			for (var row : or.entrySet()) {
				for (int x : row.getValue()) {
					for (int tap : and.getOrDefault(row.getKey(), List.of())) {
						assertTrue(Math.abs(x - tap) <= 14, gadget.kind() + ": the OR tap at x " + x + " is " + Math.abs(x - tap) + " from the AND tap at x "
								+ tap + " in the row at gz " + row.getKey());
					}
				}
			}
		}
		IllegalArgumentException far = assertThrows(IllegalArgumentException.class, () -> Pla.build(List.of(Set.of(1)), Set.of(),
				new Pla.Layout("far", List.of("a", "b", "c", "d"), List.of("x"), List.of(0), Set.of())));
		assertEquals("output slot 0 is out of the AND taps' reach: with 4 inputs an array's output slots go from 2 to 14", far.getMessage());
	}

	/**
	 * An array in sections minimizes each on its own and lays its rows one section after another: an output of an earlier
	 * section leaving the front may share a slot with one of a later section leaving the back, their lines a block apart.
	 */
	@Test
	void sectionsShareSlotsFrontThenBack() {
		Gadget two = Pla.build(List.of(Set.of(1, 3), Set.of(3)), Set.of(), new Pla.Layout("two", List.of("a", "b"), List.of("f", "g"), List.of(2, 2),
				Set.of(0), List.of(0, 1)));
		List<Integer> zs = two.blocks().stream().filter(b -> b.pos().x() == 2 && b.pos().y() == 1).map(b -> b.pos().z()).sorted().toList();
		assertEquals(0, zs.getFirst(), "the front exit's buffer");
		assertEquals(two.size().z() - 1, zs.getLast(), "the back exit's buffer");
		int gaps = 0;
		for (int i = 1; i < zs.size(); i++) {
			if (zs.get(i) - zs.get(i - 1) > 1) {
				gaps++;
			}
		}
		assertEquals(1, gaps, "two lines at slot 2, a block apart: " + zs);
		assertEquals(List.of("a", "b", "f", "g"), two.ports().stream().map(Port::name).toList());
		assertThrows(IllegalArgumentException.class, () -> Pla.build(List.of(Set.of(1, 3), Set.of(3)), Set.of(), new Pla.Layout("two",
				List.of("a", "b"), List.of("f", "g"), List.of(2, 2), Set.of(1), List.of(0, 1))), "a back exit before a front exit in the same slot");
	}
}
