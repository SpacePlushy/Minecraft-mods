package io.palmisano.aibuilder.core.request;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.shapes.Box;

/**
 * The build a player's next prompt can refine (spec §5.4), saved with the world: where it is (the dimension, the
 * frame's world origin and facing, its size), what it's called, the box of everything it has changed so far (local),
 * the Claude Code session to resume, the prompts so far, how many of them are still built (each is its own undo
 * entry), whether the last was undone (Claude is told so with the next prompt), and the moving build its moving parts
 * are in ("" for none, spec v2 §4.4).
 */
public record BuildSession(String dimension, String title, Pos origin, Facing facing, SizeTier size, Box changed, String claudeSession,
		List<String> prompts, int builds, boolean lastUndone, String movingBuild) {
	public BuildSession {
		prompts = List.copyOf(prompts);
		movingBuild = movingBuild == null ? "" : movingBuild;
	}

	/** The same session, its moving parts in the moving build {@code id}. */
	public BuildSession withMovingBuild(String id) {
		return new BuildSession(dimension, title, origin, facing, size, changed, claudeSession, prompts, builds, lastUndone, id);
	}

	/** One more prompt built: its changes join the box; the session is the one Claude Code ran; a new title takes over. */
	public BuildSession refined(String prompt, Box changes, String session, String newTitle) {
		List<String> all = new ArrayList<>(prompts);
		all.add(prompt);
		Box joined = Box.of(Math.min(changed.min().x(), changes.min().x()), Math.min(changed.min().y(), changes.min().y()),
				Math.min(changed.min().z(), changes.min().z()), Math.max(changed.max().x(), changes.max().x()),
				Math.max(changed.max().y(), changes.max().y()), Math.max(changed.max().z(), changes.max().z()));
		return new BuildSession(dimension, newTitle.isBlank() ? title : newTitle, origin, facing, size, joined, session, all, builds + 1, false,
				movingBuild);
	}

	/** Its newest build undone: the session goes on without it, or, with its first build gone, ends. */
	public Optional<BuildSession> undone() {
		if (builds <= 1) {
			return Optional.empty();
		}
		return Optional.of(new BuildSession(dimension, title, origin, facing, size, changed, claudeSession, prompts, builds - 1, true, movingBuild));
	}
}
