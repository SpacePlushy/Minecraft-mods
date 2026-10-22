package io.palmisano.aibuilder.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;
import io.palmisano.aibuilder.core.agent.McpHandler;
import io.palmisano.aibuilder.core.agent.McpSession;
import io.palmisano.aibuilder.core.agent.McpSessions;
import io.palmisano.aibuilder.core.tools.ToolCatalog;
import io.palmisano.aibuilder.core.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The MCP server over real HTTP on loopback, as Claude Code reaches it. */
class McpHttpServerTest {
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final McpSessions sessions = new McpSessions();
	private final CountDownLatch release = new CountDownLatch(1);
	private final String token = sessions.open(new McpSession() {
		@Override
		public boolean awaitReady(long millis) {
			return true;
		}

		@Override
		public ToolResult call(String name, JsonObject arguments) {
			if (name.equals("checkpoint")) {
				try {
					release.await(20, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			return new ToolResult(name + " done", false);
		}
	});
	private McpHttpServer server;

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop();
		}
	}

	private HttpRequest.Builder request(String body) {
		return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/mcp")).header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream").timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.ofString(body));
	}

	private static String call(String name) {
		return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name + "\",\"arguments\":{}}}";
	}

	@Test
	void initializeOverRealHttp() throws Exception {
		server = McpHttpServer.start(new McpHandler(ToolCatalog.load().tools(), sessions));
		HttpResponse<String> response = http.send(request("{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
				+ "\"2025-11-25\"}}").header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("application/json"), response.headers().toString());
		assertTrue(response.body().contains("\"protocolVersion\":\"2025-11-25\""), response.body());
	}

	/** No OAuth hint on a refusal: Claude Code would try to log in to us (spec §12.2). */
	@Test
	void refusalsCarryNoLoginHint() throws Exception {
		server = McpHttpServer.start(new McpHandler(ToolCatalog.load().tools(), sessions));
		HttpResponse<String> response = http.send(request(call("fill")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(401, response.statusCode());
		assertFalse(response.headers().firstValue("www-authenticate").isPresent(), response.headers().toString());
	}

	@Test
	void notificationsHaveNoBody() throws Exception {
		server = McpHttpServer.start(new McpHandler(ToolCatalog.load().tools(), sessions));
		HttpResponse<String> response = http.send(request("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
				.header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(202, response.statusCode());
		assertEquals("", response.body());
	}

	/** Claude Code may send a turn's tool calls at once; one waiting (a checkpoint) mustn't hold the others up. */
	@Test
	void concurrentCallsAreAllAnswered() throws Exception {
		server = McpHttpServer.start(new McpHandler(ToolCatalog.load().tools(), sessions));
		CompletableFuture<HttpResponse<String>> waiting = http.sendAsync(request(call("checkpoint")).header("Authorization", "Bearer " + token).build(),
				HttpResponse.BodyHandlers.ofString());
		HttpResponse<String> quick = http.send(request(call("get_context")).header("Authorization", "Bearer " + token).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, quick.statusCode());
		assertTrue(quick.body().contains("get_context done"), quick.body());
		assertFalse(waiting.isDone(), "the checkpoint answered before it was released");
		release.countDown();
		HttpResponse<String> slow = waiting.get(20, TimeUnit.SECONDS);
		assertTrue(slow.body().contains("checkpoint done"), slow.body());
	}

	@Test
	void listensOnLoopbackOnly() throws Exception {
		server = McpHttpServer.start(new McpHandler(ToolCatalog.load().tools(), sessions));
		assertTrue(server.address().getAddress().isLoopbackAddress(), server.address().toString());
		assertEquals("127.0.0.1", server.address().getAddress().getHostAddress());
	}

	@Test
	void hugeBodiesAreRefused() throws Exception {
		server = McpHttpServer.start(new McpHandler(ToolCatalog.load().tools(), sessions));
		String huge = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"pad\":\"" + "x".repeat(9 * 1024 * 1024) + "\"}";
		HttpResponse<String> response = http.send(request(huge).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(413, response.statusCode());
	}
}
