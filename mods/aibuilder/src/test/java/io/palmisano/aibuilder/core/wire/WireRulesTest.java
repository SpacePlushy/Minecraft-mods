package io.palmisano.aibuilder.core.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.ProtectionMask;
import io.palmisano.aibuilder.core.shapes.Box;
import org.junit.jupiter.api.Test;

/** What a wire may touch (spec v2 §5.4): refused whole, saying where, if it would join or set off anything but its two ports. */
class WireRulesTest {
	private static final Box ALLOWED = Box.of(-20, -2, -20, 20, 20, 40);
	/** Along z at y 1 from (0, 1, 0) to (0, 1, 10), over ground at y 0 up to z 5 and air past it. */
	private static final List<Pos> SPOTS = WirePath.trace(List.of(new Pos(0, 1, 0), new Pos(0, 1, 10)));

	private static Map<Pos, Cell> world() {
		Map<Pos, Cell> cells = new HashMap<>();
		for (Pos p : WireRules.needed(SPOTS)) {
			cells.put(p, p.y() <= 0 && p.z() <= 5 ? Cell.SOLID : Cell.FREE);
		}
		return cells;
	}

	private static String refusal(Map<Pos, Cell> cells) {
		return assertThrows(WireRules.Refused.class, () -> WireRules.supports(SPOTS, cells, Set.of(), ALLOWED, ProtectionMask.none())).getMessage();
	}

	@Test
	void aWireNeedsStoneOnlyWhereTheGroundIsMissing() {
		assertEquals(Set.of(new Pos(0, 0, 6), new Pos(0, 0, 7), new Pos(0, 0, 8), new Pos(0, 0, 9), new Pos(0, 0, 10)),
				WireRules.supports(SPOTS, world(), Set.of(), ALLOWED, ProtectionMask.none()));
	}

	/** Dust beside the wire or a step from it, other redstone beside its dust or under it: refused, saying where. */
	@Test
	void aWireTouchingAnotherIsRefusedSayingWhere() {
		Map<Pos, Cell> beside = world();
		beside.put(new Pos(1, 1, 3), Cell.DUST);
		assertEquals("(1, 1, 3) is dust, beside the wire at (0, 1, 3): keep a block between them (to cross a wire, go over it: a via point"
				+ " 2 above)", refusal(beside));
		Map<Pos, Cell> step = world();
		step.put(new Pos(-1, 2, 4), Cell.DUST);
		assertEquals("(-1, 2, 4) is dust, a step from the wire at (0, 1, 4): keep a block between them (to cross a wire, go over it: a via"
				+ " point 2 above)", refusal(step));
		Map<Pos, Cell> lamp = world();
		lamp.put(new Pos(1, 0, 8), Cell.COMPONENT);
		assertEquals("(1, 0, 8) is redstone, beside the stone under the wire at (0, 1, 8): keep a block between them (to cross a wire, go"
				+ " over it: a via point 2 above)", refusal(lamp));
		// Under the stone under it is fine: that's going over another wire.
		Map<Pos, Cell> under = world();
		under.put(new Pos(0, -1, 8), Cell.DUST);
		assertEquals(5, WireRules.supports(SPOTS, under, Set.of(), ALLOWED, ProtectionMask.none()).size());
		// The two ports' buffers are what its ends join.
		Map<Pos, Cell> buffers = world();
		buffers.put(new Pos(0, 1, -1), Cell.COMPONENT);
		buffers.put(new Pos(0, 1, 11), Cell.COMPONENT);
		assertEquals(5, WireRules.supports(SPOTS, buffers, Set.of(new Pos(0, 1, -1), new Pos(0, 1, 11)), ALLOWED, ProtectionMask.none()).size());
		assertEquals("(0, 1, -1) is redstone, beside the wire at (0, 1, 0): keep a block between them (to cross a wire, go over it: a via point"
				+ " 2 above)", refusal(buffers));
	}

	/** Past the box, in a block, on what dust can't stand on, or climbing under a block: refused, saying where. */
	@Test
	void aWireThatCantFitIsRefusedWhole() {
		WireRules.Refused out = assertThrows(WireRules.Refused.class,
				() -> WireRules.supports(SPOTS, world(), Set.of(), Box.of(-20, -2, -20, 20, 20, 8), ProtectionMask.none()));
		assertEquals("out_of_bounds", out.code());
		assertEquals("the wire would run through (0, 1, 9), past the allowed box", out.getMessage());
		Map<Pos, Cell> wall = world();
		wall.put(new Pos(0, 1, 4), Cell.SOLID);
		assertEquals("(0, 1, 4) holds a block, where the wire's dust would go", refusal(wall));
		Map<Pos, Cell> glass = world();
		glass.put(new Pos(0, 0, 2), Cell.OTHER);
		assertEquals("dust can't stand on what's at (0, 0, 2), under the wire at (0, 1, 2)", refusal(glass));
		ProtectionMask mask = new ProtectionMask(ALLOWED);
		mask.protect(new Pos(0, 0, 7));
		WireRules.Refused mine = assertThrows(WireRules.Refused.class, () -> WireRules.supports(SPOTS, world(), Set.of(), ALLOWED, mask));
		assertEquals("protected", mine.code());
		assertEquals("something player-made is at (0, 0, 7), where the stone under the wire would go", mine.getMessage());
		List<Pos> climbing = WirePath.trace(List.of(new Pos(0, 1, 0), new Pos(0, 2, 3)));
		Map<Pos, Cell> roof = new HashMap<>();
		for (Pos p : WireRules.needed(climbing)) {
			roof.put(p, p.y() <= 0 ? Cell.SOLID : Cell.FREE);
		}
		roof.put(new Pos(0, 2, 0), Cell.SOLID);
		WireRules.Refused low = assertThrows(WireRules.Refused.class, () -> WireRules.supports(climbing, roof, Set.of(), ALLOWED, ProtectionMask.none()));
		assertEquals("blocked", low.code());
		assertEquals("the wire climbs from (0, 1, 0) to (0, 2, 1), which needs (0, 2, 0) empty", low.getMessage());
	}

	/** A block beside the wire that something powers strongly (a lever on it, a torch under it) would power the wire: refused (M9 review). */
	@Test
	void aWireBesideAPoweredBlockIsRefused() {
		Map<Pos, Cell> post = world();
		post.put(new Pos(1, 1, 4), Cell.SOLID);
		post.put(new Pos(1, 2, 4), Cell.COMPONENT);
		assertEquals("(1, 2, 4) is redstone, beside the block at (1, 1, 4) next to the wire at (0, 1, 4): keep a block between them (to cross a wire,"
				+ " go over it: a via point 2 above)", refusal(post));
		Map<Pos, Cell> roof = world();
		roof.put(new Pos(0, 2, 6), Cell.SOLID);
		roof.put(new Pos(0, 3, 6), Cell.COMPONENT);
		assertEquals("(0, 3, 6) is redstone, beside the block at (0, 2, 6) next to the wire at (0, 1, 6): keep a block between them (to cross a wire,"
				+ " go over it: a via point 2 above)", refusal(roof));
		Map<Pos, Cell> wall = world();
		wall.put(new Pos(-1, 1, 9), Cell.SOLID);
		assertEquals(5, WireRules.supports(SPOTS, wall, Set.of(), ALLOWED, ProtectionMask.none()).size(), "a block with nothing redstone by it is fine");
	}
}
