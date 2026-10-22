package io.palmisano.aibuilder.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.palmisano.aibuilder.core.blocks.FakeCatalog;
import io.palmisano.aibuilder.core.blocks.Validator;
import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.LocalFrame;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.place.Placement;
import io.palmisano.aibuilder.core.request.Messages;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.core.site.BuildBox;
import io.palmisano.aibuilder.core.tools.Budget;
import io.palmisano.aibuilder.core.tools.FakeWorld;
import io.palmisano.aibuilder.core.tools.RecordingSink;
import io.palmisano.aibuilder.core.tools.ToolExecutor;
import org.junit.jupiter.api.Test;

class ScriptedBackendTest {
	private static final BuildRequest REQUEST = new BuildRequest("test", SizeTier.SMALL);
	private static final String BRIEF = "{\"tool\":\"submit_brief\",\"args\":{\"title\":\"t\",\"style\":\"s\"}}";
	private static final String FILL = "{\"tool\":\"fill\",\"args\":{\"box\":[0,0,0,1,0,0],\"block\":\"stone\"}}";

	private static ToolExecutor tools(RecordingSink sink) {
		return new ToolExecutor(new Validator(FakeCatalog.standard(), Validator.DEFAULT_BANNED),
				new LocalFrame(new Pos(0, 0, 0), Facing.SOUTH), Box.of(-8, -2, 0, 7, 15, 15), SizeTier.SMALL, 100_000, sink, new Budget(150),
				new FakeWorld());
	}

	private static String script(String... calls) {
		return "{\"calls\":[" + String.join(",", calls) + "]}";
	}

	@Test
	void runsEveryCallInOrderAndFinishes() throws Exception {
		RecordingSink sink = new RecordingSink();
		BuildHandle handle = new ScriptedBackend(script(BRIEF, FILL, "{\"tool\":\"finish\",\"args\":{\"summary\":\"done\"}}"), Runnable::run)
				.start(REQUEST, tools(sink), BuildEvents.NONE);
		assertEquals(BuildOutcome.FINISHED, handle.outcome().get(5, TimeUnit.SECONDS));
		assertEquals("t", sink.brief.title());
		assertEquals(2, sink.queuedCount());
		assertEquals("done", sink.finishedWith);
	}

	/**
	 * A real build replays (spec §16, §17): the M5 live castle, converted from its stream log by
	 * tools/stream_to_script.py. Every call still reaches a tool and none is refused for its arguments; blocks the fake
	 * catalog doesn't know are only rejected entries.
	 */
	@Test
	void aRealBuildReplays() throws Exception {
		String castle;
		try (InputStream in = ScriptedBackendTest.class.getResourceAsStream("/scripts/castle-m5.json")) {
			castle = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		RecordingSink sink = new RecordingSink();
		ToolExecutor tools = new ToolExecutor(new Validator(FakeCatalog.standard(), Validator.DEFAULT_BANNED), new LocalFrame(new Pos(0, 64, 0),
				Facing.SOUTH), BuildBox.allowed(SizeTier.MEDIUM, 64, -64, 320).orElseThrow(), SizeTier.MEDIUM, 100_000, sink, new Budget(150),
				new FakeWorld());
		List<String> answers = new CopyOnWriteArrayList<>();
		tools.listen((name, args, result, millis) -> answers.add(name + ": " + result.text()));
		BuildHandle handle = new ScriptedBackend(castle, Runnable::run).start(new BuildRequest("a medieval stone castle with a gatehouse",
				SizeTier.MEDIUM), tools, BuildEvents.NONE);
		assertEquals(BuildOutcome.FINISHED, handle.outcome().get(60, TimeUnit.SECONDS));
		assertEquals(107, answers.size());
		List<String> broken = answers.stream().filter(a -> a.contains("There is no tool") || a.contains(": bad_argument")).toList();
		assertEquals(List.of(), broken);
		assertEquals("Stonehold Castle with Gatehouse", sink.brief.title());
		assertTrue(sink.queuedCount() > 5_000, "queued " + sink.queuedCount());
	}

	@Test
	void aScriptWithoutFinishIsFinishedForIt() throws Exception {
		RecordingSink sink = new RecordingSink();
		BuildHandle handle = new ScriptedBackend(script(BRIEF, FILL), Runnable::run).start(REQUEST, tools(sink), BuildEvents.NONE);
		assertEquals(BuildOutcome.FINISHED, handle.outcome().get(5, TimeUnit.SECONDS));
		assertTrue(sink.finishedWith != null, "finish should have been called");
	}

	@Test
	void aCallThatThrowsFailsTheBuildWithItsReason() throws Exception {
		RecordingSink sink = new RecordingSink() {
			@Override
			public void queue(List<Placement> batch) {
				throw new IllegalStateException("the world is read-only");
			}
		};
		BuildHandle handle = new ScriptedBackend(script(BRIEF, FILL), Runnable::run).start(REQUEST, tools(sink), BuildEvents.NONE);
		assertEquals(BuildOutcome.FAILED, handle.outcome().get(5, TimeUnit.SECONDS));
		// A backend's reason is the player's whole line: BuildManager shows it as it is (M4 review).
		assertEquals(Messages.failed("the world is read-only"), handle.reason());
	}

	/** An Error (out of memory, a stack overflow) must still end the build, or the server would stay busy forever. */
	@Test
	void anErrorThatIsntAnExceptionStillEndsTheBuild() throws Exception {
		RecordingSink sink = new RecordingSink() {
			@Override
			public void queue(List<Placement> batch) {
				throw new StackOverflowError("too deep");
			}
		};
		BuildHandle handle = new ScriptedBackend(script(BRIEF, FILL), Runnable::run).start(REQUEST, tools(sink), BuildEvents.NONE);
		assertEquals(BuildOutcome.FAILED, handle.outcome().get(5, TimeUnit.SECONDS));
		assertTrue(handle.reason().contains("too deep"), handle.reason());
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
					.start(REQUEST, tools(sink), BuildEvents.NONE);
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
