package io.palmisano.aibuilder.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import io.palmisano.aibuilder.AiBuilderMod;
import io.palmisano.aibuilder.core.request.PromptHistory;
import net.fabricmc.loader.api.FabricLoader;

/** The recent prompts and last size, kept in the client's config folder. Never throws: a bad file reads as empty. */
public final class PromptHistoryStore {
	private final Path file;

	public PromptHistoryStore(Path file) {
		this.file = file;
	}

	public static PromptHistoryStore standard() {
		return new PromptHistoryStore(FabricLoader.getInstance().getConfigDir().resolve("aibuilder-history.json"));
	}

	public PromptHistory load() {
		if (!Files.exists(file)) {
			return PromptHistory.empty();
		}
		try {
			return PromptHistory.fromJson(Files.readString(file));
		} catch (IOException e) {
			AiBuilderMod.LOG.warn("Couldn't read {}; starting with no recent prompts", file, e);
			return PromptHistory.empty();
		}
	}

	/** Writes a sibling file and moves it over the old one, so a crash mid-write never leaves half a file. */
	public void save(PromptHistory history) {
		Path partial = file.resolveSibling(file.getFileName() + ".tmp");
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(partial, history.toJson());
			Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			AiBuilderMod.LOG.warn("Couldn't save {}", file, e);
		}
	}
}
