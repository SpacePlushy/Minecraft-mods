package io.palmisano.aibuilder.backend;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.palmisano.aibuilder.core.agent.McpHandler;
import io.palmisano.aibuilder.core.agent.McpRequest;
import io.palmisano.aibuilder.core.agent.McpResponse;

/**
 * The MCP server's transport (spec §12.2): the JDK's HTTP server on 127.0.0.1, a free port, path {@code /mcp}. A
 * few threads, so a checkpoint waiting on placement never holds up the turn's other calls.
 */
public final class McpHttpServer {
	/** Far above any real call (2,000 set_blocks entries are about 200 KB). */
	static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
	private static final int THREADS = 8;

	private final HttpServer server;
	private final ExecutorService threads;

	private McpHttpServer(HttpServer server, ExecutorService threads) {
		this.server = server;
		this.threads = threads;
	}

	public static McpHttpServer start(McpHandler handler) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
		AtomicInteger count = new AtomicInteger();
		ExecutorService threads = Executors.newFixedThreadPool(THREADS, task -> {
			Thread t = new Thread(task, "aibuilder-mcp-" + count.incrementAndGet());
			t.setDaemon(true);
			return t;
		});
		server.createContext("/mcp", exchange -> serve(handler, exchange));
		server.setExecutor(threads);
		server.start();
		return new McpHttpServer(server, threads);
	}

	public int port() {
		return server.getAddress().getPort();
	}

	public InetSocketAddress address() {
		return server.getAddress();
	}

	public void stop() {
		server.stop(0);
		threads.shutdownNow();
	}

	private static void serve(McpHandler handler, HttpExchange exchange) throws IOException {
		try (exchange) {
			byte[] body;
			try (InputStream in = exchange.getRequestBody()) {
				body = in.readNBytes(MAX_BODY_BYTES + 1);
			}
			if (body.length > MAX_BODY_BYTES) {
				exchange.sendResponseHeaders(413, -1);
				return;
			}
			Map<String, String> headers = new HashMap<>();
			for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
				if (header.getKey() != null && !header.getValue().isEmpty()) {
					headers.put(header.getKey().toLowerCase(Locale.ROOT), header.getValue().getFirst());
				}
			}
			McpResponse response = handler.handle(new McpRequest(exchange.getRequestMethod(), headers, new String(body, StandardCharsets.UTF_8)));
			if (response.body() == null) {
				exchange.sendResponseHeaders(response.status(), -1);
				return;
			}
			byte[] out = response.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(response.status(), out.length);
			try (OutputStream stream = exchange.getResponseBody()) {
				stream.write(out);
			}
		}
	}
}
