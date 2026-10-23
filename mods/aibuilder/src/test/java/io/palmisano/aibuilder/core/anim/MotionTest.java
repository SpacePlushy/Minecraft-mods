package io.palmisano.aibuilder.core.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** A motion as Claude gives it to animate (spec v2 §4.2): one of swing, spin or bob, within the limits. */
class MotionTest {
	private static Motion parse(String json) {
		return Motion.parse(JsonParser.parseString(json).getAsJsonObject());
	}

	private static String refusal(String json) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		return assertThrows(IllegalArgumentException.class, () -> Motion.parse(o)).getMessage();
	}

	@Test
	void eachKindParses() {
		assertEquals(new Motion.Swing("forward", -30, 40, 40, 0), parse("{\"swing\": {\"axis\": \"forward\", \"from\": -30, \"to\": 40, \"period\": 40}}"));
		assertEquals(new Motion.Spin("up", -200, 0.25), parse("{\"spin\": {\"axis\": \"Up\", \"period\": -200, \"phase\": 0.25}}"));
		assertEquals(new Motion.Bob("up", 0.5, 60, 0), parse("{\"bob\": {\"direction\": \"up\", \"distance\": 0.5, \"period\": 60}}"));
	}

	@Test
	void limitsAreRefusedWithTheirReason() {
		assertTrue(refusal("{\"swing\": {\"axis\": \"up\", \"from\": 0, \"to\": 40, \"period\": 9}}").contains("period"));
		assertTrue(refusal("{\"swing\": {\"axis\": \"up\", \"from\": 0, \"to\": 181, \"period\": 40}}").contains("-180 to 180"));
		assertTrue(refusal("{\"bob\": {\"direction\": \"up\", \"distance\": 17, \"period\": 40}}").contains("0 to 16"));
		assertTrue(refusal("{\"swing\": {\"axis\": \"up\", \"from\": 0, \"to\": 40, \"period\": 40}, \"spin\": {\"axis\": \"up\", \"period\": 40}}")
				.contains("one of swing, spin or bob"));
		assertTrue(refusal("{\"spin\": {\"axis\": \"north\", \"period\": 40}}").contains("forward, back, left, right, up or down"));
		assertTrue(refusal("{\"spin\": {\"axis\": \"up\", \"period\": 0}}").contains("period"));
		assertTrue(refusal("{\"spin\": {\"axis\": \"up\", \"period\": 40.5}}").contains("whole"));
		assertTrue(refusal("{\"bob\": {\"direction\": \"up\", \"distance\": 1, \"period\": -40}}").contains("period"));
		assertTrue(refusal("{\"swing\": {\"axis\": \"up\", \"from\": 0, \"to\": 40, \"period\": 40, \"phase\": 1.5}}").contains("phase"));
		assertTrue(refusal("{\"wiggle\": {}}").contains("one of swing, spin or bob"));
	}

	/**
	 * The game glides the short way between two poses, and a pose goes out every 4 ticks: a motion that turns half a
	 * turn or more in that time would turn the wrong way (Review Focus 5). The fastest spin allowed is under it.
	 */
	@Test
	void aFastSpinStepsUnderHalfATurn() {
		assertTrue(Poses.stepDegrees(new Motion.Spin("up", 10, 0)) < 180, "a period-10 spin");
		assertTrue(Poses.stepDegrees(new Motion.Spin("up", -10, 0)) < 180, "a period-10 spin the other way");
		assertEquals(0, Poses.stepDegrees(new Motion.Bob("up", 16, 10, 0)));
		assertTrue(refusal("{\"swing\": {\"axis\": \"up\", \"from\": -180, \"to\": 180, \"period\": 10}}").contains("too fast"));
	}
}
