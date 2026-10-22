package io.palmisano.aibuilder.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Claude Code's stream-json, from the M4 spike's real recordings (src/test/resources/stream/). */
class StreamEventsTest {
	static List<StreamEvent> read(String fixture) {
		try (InputStream in = StreamEventsTest.class.getResourceAsStream("/stream/" + fixture)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(StreamEvents::parse).flatMap(Optional::stream).toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static <T> List<T> only(List<StreamEvent> events, Class<T> type) {
		return events.stream().filter(type::isInstance).map(type::cast).toList();
	}

	@Test
	void successFixtureReads() {
		List<StreamEvent> events = read("success.jsonl");
		List<StreamEvent.Init> inits = only(events, StreamEvent.Init.class);
		assertEquals(1, inits.size());
		StreamEvent.Init init = inits.getFirst();
		assertEquals(List.of("mcp__aibuilder__finish", "mcp__aibuilder__get_context"), init.tools());
		assertEquals(List.of(new StreamEvent.Server("aibuilder", "connected")), init.servers());
		assertEquals("none", init.apiKeySource());
		assertEquals("claude-opus-5-5", init.model());
		assertEquals("2.1.306", init.version());
		assertEquals("ea63cf99-ab48-456c-8496-0097e064226d", init.sessionId());
		assertEquals(3, init.plugins().size());
		assertTrue(init.plugins().stream().allMatch(p -> p.path().equals("builtin")), init.plugins().toString());

		List<StreamEvent.Assistant> turns = only(events, StreamEvent.Assistant.class);
		assertEquals(List.of("mcp__aibuilder__get_context", "mcp__aibuilder__finish"),
				turns.stream().flatMap(a -> a.toolNames().stream()).toList());
		assertTrue(turns.getLast().text().startsWith("I called get_context once"), turns.getLast().text());
		assertTrue(turns.stream().allMatch(a -> a.error().isEmpty() && !a.messageId().isEmpty()));

		// message_delta carries each message's final usage: together, the result's totals.
		List<StreamEvent.Usage> usage = only(events, StreamEvent.Usage.class);
		assertEquals(3, usage.size());
		assertEquals(135, usage.stream().mapToLong(StreamEvent.Usage::output).sum());
		assertEquals(6, usage.stream().mapToLong(StreamEvent.Usage::input).sum());
		assertEquals(2534, usage.stream().mapToLong(StreamEvent.Usage::cacheRead).sum());
		assertEquals(1414, usage.stream().mapToLong(StreamEvent.Usage::cacheWrite).sum());

		List<StreamEvent.RateLimit> limits = only(events, StreamEvent.RateLimit.class);
		// The fullest window decides how close the plan is to its limit (here the seven-day one, at 51%).
		assertEquals(List.of(new StreamEvent.RateLimit("allowed", 1792681200L, "five_hour", false, "rejected", 0.51)), limits);

		List<StreamEvent.Result> results = only(events, StreamEvent.Result.class);
		assertEquals(1, results.size());
		StreamEvent.Result result = results.getFirst();
		assertEquals("success", result.subtype());
		assertFalse(result.isError());
		assertEquals(3, result.turns());
		assertEquals("completed", result.terminalReason());
		assertTrue(result.text().startsWith("I called get_context once"), result.text());
	}

	@Test
	void loggedOutFixtureReads() {
		List<StreamEvent> events = read("logged_out.jsonl");
		StreamEvent.Assistant turn = only(events, StreamEvent.Assistant.class).getFirst();
		assertEquals("authentication_failed", turn.error());
		assertEquals("Not logged in · Please run /login", turn.text());
		StreamEvent.Result result = only(events, StreamEvent.Result.class).getFirst();
		assertTrue(result.isError());
		assertEquals("api_error", result.terminalReason());
	}

	@Test
	void cancelledFixtureReads() {
		StreamEvent.Result result = only(read("cancelled.jsonl"), StreamEvent.Result.class).getFirst();
		assertEquals("error_during_execution", result.subtype());
		assertTrue(result.isError());
		assertEquals("aborted_tools", result.terminalReason());
		assertEquals("", result.text());
	}

	@Test
	void otherKindsOfLinesAreOther() {
		List<StreamEvent> events = read("slow_tool.jsonl");
		// system/status, the partial-message events other than message_delta, user tool results, tool_progress.
		assertTrue(only(events, StreamEvent.Other.class).stream().anyMatch(o -> o.type().equals("tool_progress")));
		assertTrue(only(events, StreamEvent.Other.class).stream().anyMatch(o -> o.type().equals("system/status")));
	}

	@Test
	void retriesRead() {
		StreamEvent event = StreamEvents.parse(
				"{\"type\":\"system\",\"subtype\":\"api_retry\",\"attempt\":2,\"max_retries\":10,\"retry_delay_ms\":4000,\"error_status\":529,"
						+ "\"error\":\"overloaded_error\",\"session_id\":\"s\"}").orElseThrow();
		assertEquals(new StreamEvent.Retry(2, "overloaded_error"), event);
	}

	@Test
	void unknownEventsAndFieldsAreIgnored() {
		assertEquals(Optional.of(new StreamEvent.Other("brand_new_event")),
				StreamEvents.parse("{\"type\":\"brand_new_event\",\"whatever\":[1,2,{\"x\":null}]}"));
		// An init from a later Claude Code: new fields, some old ones missing.
		StreamEvent.Init init = (StreamEvent.Init) StreamEvents.parse("{\"type\":\"system\",\"subtype\":\"init\",\"tools\":[\"mcp__aibuilder__fill\"],"
				+ "\"mcp_servers\":[{\"name\":\"aibuilder\",\"status\":\"connected\",\"transport\":{\"kind\":\"http\"}}],\"apiKeySource\":\"none\","
				+ "\"hologram_mode\":true}").orElseThrow();
		assertEquals(List.of("mcp__aibuilder__fill"), init.tools());
		assertEquals(List.of(), init.plugins());
		assertEquals("", init.version());
		// A rate limit without its info, a result without numbers: defaults, no exception.
		assertEquals(Optional.of(new StreamEvent.RateLimit("", 0, "", false, "", 0)), StreamEvents.parse("{\"type\":\"rate_limit_event\"}"));
		StreamEvent.Result result = (StreamEvent.Result) StreamEvents.parse("{\"type\":\"result\",\"subtype\":\"success\"}").orElseThrow();
		assertEquals(new StreamEvent.Result("success", false, 0, "", ""), result);
		// Wrong types where a value is expected don't throw either.
		assertTrue(StreamEvents.parse("{\"type\":\"result\",\"num_turns\":\"many\",\"is_error\":{}}").isPresent());
		assertTrue(StreamEvents.parse("{\"type\":\"assistant\",\"message\":\"text\"}").isPresent());
	}

	@Test
	void garbageIsSkipped() {
		assertEquals(Optional.empty(), StreamEvents.parse(""));
		assertEquals(Optional.empty(), StreamEvents.parse("   "));
		assertEquals(Optional.empty(), StreamEvents.parse("Loading…"));
		assertEquals(Optional.empty(), StreamEvents.parse("{\"type\":"));
		assertEquals(Optional.empty(), StreamEvents.parse("[1,2,3]"));
		assertEquals(Optional.empty(), StreamEvents.parse("{\"no_type\":1}"));
	}
}
