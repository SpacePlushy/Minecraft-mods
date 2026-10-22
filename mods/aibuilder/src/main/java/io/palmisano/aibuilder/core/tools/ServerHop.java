package io.palmisano.aibuilder.core.tools;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Runs a world read on the server's thread for a tool on another thread, and never anywhere else. Once a server is
 * stopping its executor runs tasks inline on the caller's thread, where a read could wait forever for a chunk the
 * server thread will never load again (M3 review); such a read fails as {@link WorldReader.Unreadable} instead.
 */
public final class ServerHop {
	private ServerHop() {
	}

	public static <T> CompletableFuture<T> submit(Executor server, BooleanSupplier onServerThread, BooleanSupplier stopping, Supplier<T> read) {
		CompletableFuture<T> future = new CompletableFuture<>();
		if (stopping.getAsBoolean()) {
			future.completeExceptionally(new WorldReader.Unreadable("the world is closing"));
			return future;
		}
		server.execute(() -> {
			if (!onServerThread.getAsBoolean()) {
				future.completeExceptionally(new WorldReader.Unreadable("the world is closing"));
				return;
			}
			try {
				future.complete(read.get());
			} catch (RuntimeException e) {
				future.completeExceptionally(e);
			}
		});
		return future;
	}
}
