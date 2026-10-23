package io.palmisano.aibuilder.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogWriterTest {
	@TempDir
	Path temp;

	@Test
	void linesArriveInOrder() throws Exception {
		Path file = temp.resolve("logs/aibuilder/build.jsonl");
		LogWriter writer = new LogWriter(file);
		IntStream.range(0, 500).forEach(i -> writer.accept("line " + i));
		writer.close().get(10, TimeUnit.SECONDS);
		assertEquals(IntStream.range(0, 500).mapToObj(i -> "line " + i).toList(), Files.readAllLines(file));
	}

	/** A tool call that lands after the outcome (the log is closed) is left out, not written to a reopened file. */
	@Test
	void nothingIsWrittenAfterClose() throws Exception {
		Path file = temp.resolve("late.jsonl");
		LogWriter writer = new LogWriter(file);
		writer.accept("outcome");
		writer.close().get(10, TimeUnit.SECONDS);
		writer.accept("a late call");
		writer.close().get(10, TimeUnit.SECONDS);
		assertEquals(List.of("outcome"), Files.readAllLines(file));
	}

	@Test
	void aFileThatCantBeWrittenIsDropped() throws Exception {
		Path taken = Files.createDirectories(temp.resolve("a folder where the file would go"));
		LogWriter writer = new LogWriter(taken);
		writer.accept("one");
		writer.accept("two");
		writer.close().get(10, TimeUnit.SECONDS);
		assertEquals(List.of(), List.of(taken.toFile().list()));
	}
}
