package io.palmisano.aibuilder.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.palmisano.aibuilder.core.request.Messages;
import io.palmisano.aibuilder.core.tools.ToolExecutor;
import io.palmisano.aibuilder.core.tools.ToolResult;

/**
 * Replays a JSON list of tool calls, {@code {"calls": [{"tool": "...", "args": {...}}]}}, through the real tools, so
 * the whole pipeline runs without Claude. Calls run in order on {@code worker}; a cancel takes effect before the
 * next call. If the script doesn't call {@code finish}, the backend does.
 */
public final class ScriptedBackend implements AiBackend {
	private record Call(String tool, JsonObject args) {
	}

	/** One call's answer. */
	public record Reply(String tool, ToolResult result) {
	}

	private final List<Call> calls = new ArrayList<>();
	private final Executor worker;
	private final List<ToolResult> errors = new CopyOnWriteArrayList<>();
	private final List<Reply> replies = new CopyOnWriteArrayList<>();

	public ScriptedBackend(String scriptJson, Executor worker) {
		this.worker = worker;
		for (JsonElement e : JsonParser.parseString(scriptJson).getAsJsonObject().getAsJsonArray("calls")) {
			JsonObject call = e.getAsJsonObject();
			calls.add(new Call(call.get("tool").getAsString(), call.has("args") ? call.getAsJsonObject("args") : new JsonObject()));
		}
	}

	/** The calls that came back as errors, for tests and logs. */
	public List<ToolResult> errors() {
		return List.copyOf(errors);
	}

	/** Every call's answer so far, in order, for tests. */
	public List<Reply> replies() {
		return List.copyOf(replies);
	}

	@Override
	public BuildHandle start(BuildRequest request, ToolExecutor tools, BuildEvents events) {
		AtomicBoolean cancelled = new AtomicBoolean();
		AtomicReference<String> failure = new AtomicReference<>("");
		CompletableFuture<BuildOutcome> outcome = new CompletableFuture<>();
		worker.execute(() -> {
			try {
				for (Call call : calls) {
					if (cancelled.get()) {
						outcome.complete(BuildOutcome.CANCELLED);
						return;
					}
					ToolResult result = tools.call(call.tool(), call.args());
					replies.add(new Reply(call.tool(), result));
					if (result.isError()) {
						errors.add(result);
					}
					if (tools.finished()) {
						break;
					}
				}
				if (cancelled.get()) {
					outcome.complete(BuildOutcome.CANCELLED);
					return;
				}
				if (!tools.finished()) {
					JsonObject args = new JsonObject();
					args.addProperty("summary", "the script ended");
					tools.call("finish", args);
				}
				outcome.complete(BuildOutcome.FINISHED);
			} catch (Throwable e) {
				// Errors too (out of memory, a stack overflow): an outcome that never completes keeps the server busy.
				failure.set(Messages.failed(e.getMessage() == null ? e.toString() : e.getMessage()));
				outcome.completeExceptionally(e);
			}
		});
		return new BuildHandle() {
			@Override
			public void cancel() {
				cancelled.set(true);
			}

			@Override
			public CompletableFuture<BuildOutcome> outcome() {
				return outcome.exceptionally(e -> BuildOutcome.FAILED);
			}

			@Override
			public String reason() {
				return failure.get();
			}
		};
	}
}
