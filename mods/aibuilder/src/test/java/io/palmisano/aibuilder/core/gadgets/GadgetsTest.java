package io.palmisano.aibuilder.core.gadgets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.palmisano.aibuilder.core.blocks.BlockEntityData;
import io.palmisano.aibuilder.core.shapes.LocalBlock;
import org.junit.jupiter.api.Test;

/** The library's kinds and their options (spec v2 §5.3): what each takes, what it offers, what it refuses. */
class GadgetsTest {
	private static JsonObject options(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	private static void assertRefused(String kind, String json, String why) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Gadgets.make(kind, options(json)));
		assertEquals(why, e.getMessage());
	}

	@Test
	void badOptionsAreRefused() {
		assertRefused("adder", "{\"bits\": 0}", "bits must be a whole number from 1 to 8, not 0");
		assertRefused("adder", "{\"bits\": 9}", "bits must be a whole number from 1 to 8, not 9");
		assertRefused("adder", "{\"bits\": 2.5}", "bits must be a whole number from 1 to 8, not 2.5");
		assertRefused("adder", "{\"bits\": 2, \"speed\": 3}", "adder takes bits, not speed");
		assertRefused("lever_row", "{\"bits\": 2, \"label\": \"Way too long\"}", "label must be 1 to 8 lowercase letters, digits or _, starting with a letter");
		assertRefused("flux_capacitor", "{}", "there's no gadget called \"flux_capacitor\": the kinds are lever_row, lamp_row, adder, digit");
		assertRefused("digit", "{\"bits\": 4}", "digit takes no options, not bits");
	}

	@Test
	void eachKindNamesItsPortsForWhatTheyCarry() {
		Gadget adder = Gadgets.make("adder", options("{\"bits\": 2}"));
		assertEquals(List.of("a0", "a1", "b0", "b1", "c0", "s0", "s1", "s2"), adder.ports().stream().map(Port::name).toList());
		assertEquals(List.of(true, true, true, true, true, false, false, false), adder.ports().stream().map(Port::input).toList());
		Gadget levers = Gadgets.make("lever_row", options("{\"bits\": 3, \"label\": \"x\"}"));
		assertEquals(List.of("x0", "x1", "x2"), levers.ports().stream().map(Port::name).toList());
		assertTrue(levers.ports().stream().noneMatch(Port::input), "a lever row only gives signals");
		assertEquals(List.of("lever x0", "lever x1", "lever x2"), List.copyOf(levers.marks().keySet()));
		Gadget lamps = Gadgets.make("lamp_row", options("{\"bits\": 2}"));
		assertEquals(List.of("in0", "in1"), lamps.ports().stream().map(Port::name).toList());
		assertEquals(List.of("lamp 0", "lamp 1"), List.copyOf(lamps.marks().keySet()));
		assertEquals(Gadgets.make("lever_row", options("{}")).ports().size(), 4, "a lever row is 4 levers unless told");
	}

	/** Each lever's sign says its row and what the bit is worth. */
	@Test
	void aLeverRowsSignsSayWhatEachLeverIsWorth() {
		Gadget levers = Gadgets.make("lever_row", options("{\"bits\": 4, \"label\": \"a\"}"));
		List<List<String>> signs = levers.blocks().stream().map(LocalBlock::data).filter(d -> d instanceof BlockEntityData.Sign)
				.map(d -> ((BlockEntityData.Sign) d).front()).toList();
		assertEquals(List.of(List.of("A", "1", "", ""), List.of("A", "2", "", ""), List.of("A", "4", "", ""), List.of("A", "8", "", "")), signs);
	}

	@Test
	void anAdderGrowsWithItsBits() {
		Gadget one = Gadgets.make("adder", options("{\"bits\": 1}"));
		Gadget four = Gadgets.make("adder", options("{\"bits\": 4}"));
		assertEquals(one.size().x() + 3 * 14, four.size().x(), "cells 14 apart");
		assertTrue(four.settleTicks() > one.settleTicks(), "the carry ripples through every cell");
		assertEquals("an adder (4 bits)", Gadgets.describe("adder", options("{\"bits\": 4}")));
	}
}
