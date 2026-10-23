package io.palmisano.aibuilder.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.Box;
import org.junit.jupiter.api.Test;

/** test_redstone's steps (spec v2 §5.2): set, press, wait and read, within their limits. */
class RedstoneStepsTest {
	private static final Box ALLOWED = Box.of(-8, -2, 0, 7, 15, 15);

	private static List<RedstoneSteps.Step> parse(String json) {
		return RedstoneSteps.parse(JsonParser.parseString(json).getAsJsonArray(), ALLOWED);
	}

	private static String refusal(String json) {
		JsonArray steps = JsonParser.parseString(json).getAsJsonArray();
		return assertThrows(IllegalArgumentException.class, () -> RedstoneSteps.parse(steps, ALLOWED)).getMessage();
	}

	@Test
	void eachStepParses() {
		assertEquals(List.of(new RedstoneSteps.Set(new Pos(0, 2, 4), true), new RedstoneSteps.Press(new Pos(1, 1, 4)), new RedstoneSteps.Wait(10),
				new RedstoneSteps.Read(List.of(new Pos(0, 1, 6), new Pos(1, 1, 6)))),
				parse("[{\"set\": [0, 2, 4], \"on\": true}, {\"press\": [1, 1, 4]}, {\"wait\": 10}, {\"read\": [[0, 1, 6], [1, 1, 6]]}]"));
	}

	/** show reads each digit and number of the build; which they are and where, test_redstone fills in. */
	@Test
	void showIsParsed() {
		assertEquals(List.of(new RedstoneSteps.Wait(5), new RedstoneSteps.Show(List.of())), parse("[{\"wait\": 5}, {\"show\": true}]"));
		assertEquals("show takes true: {\"show\": true}", refusal("[{\"show\": 3}]"));
	}

	@Test
	void stepsAreHeldToTheirLimits() {
		StringBuilder many = new StringBuilder("[");
		for (int i = 0; i < 65; i++) {
			many.append(i == 0 ? "" : ",").append("{\"wait\": 1}");
		}
		assertTrue(refusal(many.append("]").toString()).contains("64 steps"));
		assertTrue(refusal("[{\"wait\": 300}, {\"wait\": 101}]").contains("400 ticks"));
		StringBuilder spots = new StringBuilder("[{\"read\": [");
		for (int i = 0; i < 33; i++) {
			spots.append(i == 0 ? "" : ",").append("[0, 1, ").append(i % 15).append("]");
		}
		assertTrue(refusal(spots.append("]}]").toString()).contains("32 spots"));
		assertTrue(refusal("[{\"set\": [0, 1, 40], \"on\": true}]").contains("allowed box"));
		assertTrue(refusal("[{\"jump\": 3}]").contains("set, press, wait, read or show"));
		assertTrue(refusal("[{\"wait\": 0}]").contains("wait"));
		assertTrue(refusal("[{\"set\": [0, 1, 4]}]").contains("on"));
		assertTrue(refusal("[]").contains("at least one"));
	}
}
