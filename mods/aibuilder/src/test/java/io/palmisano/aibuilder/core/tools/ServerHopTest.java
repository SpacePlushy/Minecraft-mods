package io.palmisano.aibuilder.core.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

/** World reads for a tool happen on the server's thread or not at all (M3 review: a stopping server runs tasks inline). */
class ServerHopTest {
	@Test
	void aReadNeverRunsOffTheServerThread() {
		AtomicBoolean ran = new AtomicBoolean();
		// A stopping server's executor runs the task right here, on the caller's own thread.
		CompletableFuture<String> read = ServerHop.submit(Runnable::run, () -> false, () -> false, () -> {
			ran.set(true);
			return "x";
		});
		ExecutionException e = assertThrows(ExecutionException.class, read::get);
		assertInstanceOf(WorldReader.Unreadable.class, e.getCause());
		assertFalse(ran.get(), "the world was read off the server thread");
	}

	@Test
	void aStoppingServerIsRefusedAtOnce() {
		CompletableFuture<String> read = ServerHop.submit(task -> {
			throw new AssertionError("nothing may be scheduled on a server that's stopping");
		}, () -> true, () -> true, () -> "x");
		assertTrue(read.isCompletedExceptionally());
	}

	@Test
	void onTheServerThreadTheReadRuns() throws Exception {
		ExecutorService server = Executors.newSingleThreadExecutor(task -> new Thread(task, "server"));
		try {
			CompletableFuture<String> read = ServerHop.submit(server, () -> Thread.currentThread().getName().equals("server"), () -> false,
					() -> Thread.currentThread().getName());
			assertEquals("server", read.get(5, TimeUnit.SECONDS));
		} finally {
			server.shutdownNow();
		}
	}

	@Test
	void aFailingReadFailsTheFuture() {
		CompletableFuture<String> read = ServerHop.submit(Runnable::run, () -> true, () -> false, () -> {
			throw new IllegalStateException("chunk gone");
		});
		ExecutionException e = assertThrows(ExecutionException.class, read::get);
		assertEquals("chunk gone", e.getCause().getMessage());
	}
}
