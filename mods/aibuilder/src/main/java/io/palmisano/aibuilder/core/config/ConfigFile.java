package io.palmisano.aibuilder.core.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * {@code config/aibuilder.json} on disk (spec §14): written with the defaults on the first run, read on every other.
 * Never throws and never rewrites a file that's there: one the player broke stays for them to fix, and the defaults
 * stand in meanwhile.
 */
public final class ConfigFile {
	public static final String NAME = "aibuilder.json";

	private ConfigFile() {
	}

	public static AiBuilderConfig load(Path configDir, Consumer<String> warn) {
		Path file = configDir.resolve(NAME);
		try {
			if (!Files.exists(file)) {
				Files.createDirectories(configDir);
				// Written whole or not at all: a crash mid-write never leaves half a file to be read next time.
				Path temp = Files.createTempFile(configDir, "aibuilder", ".json.tmp");
				Files.writeString(temp, AiBuilderConfig.DEFAULT.toJson(), StandardCharsets.UTF_8);
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				return AiBuilderConfig.DEFAULT;
			}
			AiBuilderConfig.Loaded loaded = AiBuilderConfig.load(Files.readString(file, StandardCharsets.UTF_8));
			loaded.warnings().forEach(warn);
			return loaded.config();
		} catch (IOException | RuntimeException e) {
			warn.accept("Couldn't read " + file + " (" + e + "); using the defaults");
			return AiBuilderConfig.DEFAULT;
		}
	}
}
