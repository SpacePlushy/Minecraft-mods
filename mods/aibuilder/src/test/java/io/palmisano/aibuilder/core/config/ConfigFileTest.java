package io.palmisano.aibuilder.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** config/aibuilder.json on disk (spec §14): written with the defaults on the first run; never stops the mod. */
class ConfigFileTest {
	@TempDir
	Path dir;

	@Test
	void theFileIsWrittenOnTheFirstRun() throws IOException {
		List<String> warnings = new ArrayList<>();
		assertEquals(AiBuilderConfig.DEFAULT, ConfigFile.load(dir, warnings::add));
		assertEquals(AiBuilderConfig.DEFAULT.toJson(), Files.readString(dir.resolve("aibuilder.json"), StandardCharsets.UTF_8));
		assertEquals(List.of(), warnings);
	}

	@Test
	void editsAreRead() throws IOException {
		Files.writeString(dir.resolve("aibuilder.json"), "{\"max_tool_iterations\": 40, \"op_level\": 77}");
		List<String> warnings = new ArrayList<>();
		AiBuilderConfig config = ConfigFile.load(dir, warnings::add);
		assertEquals(40, config.maxToolIterations());
		assertEquals(2, config.opLevel());
		assertEquals(1, warnings.size(), warnings.toString());
	}

	/** Windows Notepad saves UTF-8 with a byte-order mark (EF BB BF) in front: it isn't part of the JSON. */
	@Test
	void aByteOrderMarkIsSkipped() throws IOException {
		byte[] json = "{\"site_gap\": 5}".getBytes(StandardCharsets.UTF_8);
		byte[] file = new byte[json.length + 3];
		file[0] = (byte) 0xEF;
		file[1] = (byte) 0xBB;
		file[2] = (byte) 0xBF;
		System.arraycopy(json, 0, file, 3, json.length);
		Files.write(dir.resolve("aibuilder.json"), file);
		List<String> warnings = new ArrayList<>();
		assertEquals(5, ConfigFile.load(dir, warnings::add).siteGap());
		assertEquals(List.of(), warnings);
	}

	/** A file the player broke is theirs to fix: it's left as it is, and the defaults stand in. */
	@Test
	void aBrokenFileIsLeftAlone() throws IOException {
		Files.writeString(dir.resolve("aibuilder.json"), "{\"max_tool_iterations\": 40,");
		List<String> warnings = new ArrayList<>();
		assertEquals(AiBuilderConfig.DEFAULT, ConfigFile.load(dir, warnings::add));
		assertEquals("{\"max_tool_iterations\": 40,", Files.readString(dir.resolve("aibuilder.json")));
		assertTrue(warnings.getFirst().contains("isn't valid JSON"), warnings.toString());
	}

	@Test
	void anUnreadableFolderIsTheDefaults() throws IOException {
		Path notAFolder = dir.resolve("file");
		Files.writeString(notAFolder, "x");
		List<String> warnings = new ArrayList<>();
		assertEquals(AiBuilderConfig.DEFAULT, ConfigFile.load(notAFolder, warnings::add));
		assertEquals(1, warnings.size(), warnings.toString());
	}
}
