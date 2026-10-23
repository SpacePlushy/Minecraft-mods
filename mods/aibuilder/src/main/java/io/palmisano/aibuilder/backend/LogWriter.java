package io.palmisano.aibuilder.backend;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Appends lines to one file on a thread of its own, never the caller's (spec §16: the server thread never waits on a
 * disk). One thread for every log keeps each file's lines in order. A file that can't be written is dropped with one
 * warning, never the build.
 */
public final class LogWriter implements Consumer<String> {
	private static final Logger LOG = LoggerFactory.getLogger("aibuilder");
	private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
		Thread t = new Thread(task, "aibuilder-log");
		t.setDaemon(true);
		return t;
	});

	private final Path file;
	/** Writer thread only. */
	private Writer out;
	private boolean broken;
	/** Closed for good: a line that comes after (a tool call landing after the outcome) is left out. Writer thread only. */
	private boolean closed;

	public LogWriter(Path file) {
		this.file = file;
	}

	@Override
	public void accept(String line) {
		WRITER.execute(() -> write(line));
	}

	/** Closes the file once every line so far is written; the future completes then. */
	public CompletableFuture<Void> close() {
		return CompletableFuture.runAsync(() -> {
			closed = true;
			closeNow();
		}, WRITER);
	}

	private void write(String line) {
		if (broken || closed) {
			return;
		}
		try {
			if (out == null) {
				if (file.getParent() != null) {
					Files.createDirectories(file.getParent());
				}
				out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
			out.write(line);
			out.write('\n');
			out.flush();
		} catch (IOException e) {
			LOG.warn("Couldn't write the build log {}; going on without it", file, e);
			broken = true;
			closeNow();
		}
	}

	private void closeNow() {
		if (out != null) {
			try {
				out.close();
			} catch (IOException e) {
				// Nothing more to lose.
			}
			out = null;
		}
	}
}
