package io.palmisano.aibuilder.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.palmisano.aibuilder.core.blocks.FakeCatalog;
import io.palmisano.aibuilder.core.blocks.Validator;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.tools.Budget;
import io.palmisano.aibuilder.core.tools.RecordingSink;
import io.palmisano.aibuilder.core.tools.ToolExecutor;
import org.junit.jupiter.api.Test;

class ScriptedBackendTest {
	private static final BuildRequest REQUEST = new BuildRequest("test", SizeTier.SMALL);
	private static final String BRIEF = "{\"tool\":\"submit_brief\",\"args\":{\"title\":\"t\",\"style\":\"s\"}}";
	private static final String FILL = "{\"tool\":\"fill\",\"args\":{\"box\":[0,0,0,1,0,0],\"block\":\"stone\"}}";

	private static ToolExecutor tools(RecordingSink sink) {
		return new ToolExecutor(new Validator(FakeCatalog.standard(), Validator.DEFAULT_BANNED),
				new LocalFrame(new Pos(0, 0, 0), Facing.SOUTH), Box.of(-8, -2, 0, 7, 15, 15), 100_000, sink, new Budget(150));
	}

	private static String script(String... calls) {
		return "{\"calls\":[" + String.join(",", calls) + "]}";
	}

	@Test
	void runsEveryCallInOrderAndFinishes() throws Exception {
		RecordingSink sink = new RecordingSink();
		BuildHandle handle = new ScriptedBackend(script(BRIEF, FILL, "{\"tool\":\"finish\",\"args\":{\"summary\":\"done\"}}"), Runnable::run)
				.start(REQUEST, tools(sink));
		assertEquals(BuildOutcome.FINISHED, handle.outcome().get(5, TimeUnit.SECONDS));
		assertEquals("t", sink.brief.title());
		assertEquals(2, sink.queuedCount());
		assertEquals("done", sink.finishedWith);
	}

	@Test
	void aScriptWithoutFinishIsFinishedForIt() throws Exception {
		RecordingSink sink = new RecordingSink();
		BuildHandle handle = new ScriptedBackend(script(BRIEF, FILL), Runnable::run).start(REQUEST, tools(sink));
		assertEquals(BuildOutcome.FINISHED, handle.outcome().get(5, TimeUnit.SECONDS));
		assertTrue(sink.finishedWith != null, "finish should have been called");
	}

	@Test
	void cancelStopsBeforeTheNextCall() throws Exception {
		CountDownLatch inCheckpoint = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		RecordingSink sink = new RecordingSink() {
			@Override
			public int checkpoint(String phase) {
				inCheckpoint.countDown();
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return 0;
			}
		};
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			BuildHandle handle = new ScriptedBackend(script(BRIEF, "{\"tool\":\"checkpoint\",\"args\":{\"phase\":\"wait\"}}", FILL), worker)
					.start(REQUEST, tools(sink));
			assertTrue(inCheckpoint.await(5, TimeUnit.SECONDS));
			handle.cancel();
			release.countDown();
			assertEquals(BuildOutcome.CANCELLED, handle.outcome().get(5, TimeUnit.SECONDS));
			assertEquals(List.of(), sink.batches, "the fill after the checkpoint must not run");
		} finally {
			worker.shutdownNow();
		}
	}
}
