package io.palmisano.aibuilder.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PromptHistoryTest {
	@Test
	void newestFirst() {
		assertEquals(List.of("b", "a"), PromptHistory.empty().withPrompt("a").withPrompt("b").recent());
	}

	@Test
	void repeatMovesToFront() {
		assertEquals(List.of("a", "b"), PromptHistory.empty().withPrompt("a").withPrompt("b").withPrompt("a").recent());
	}

	@Test
	void keepsTheLastTen() {
		PromptHistory h = PromptHistory.empty();
		for (int i = 1; i <= 12; i++) {
			h = h.withPrompt("p" + i);
		}
		assertEquals(10, h.recent().size());
		assertEquals("p12", h.recent().getFirst());
		assertEquals("p3", h.recent().getLast());
	}

	@Test
	void blankIsIgnored() {
		assertEquals(PromptHistory.empty(), PromptHistory.empty().withPrompt("  "));
	}

	@Test
	void jsonRoundTrip() {
		PromptHistory h = PromptHistory.empty().withPrompt("castle").withPrompt("日本庭園\nline two").withSize(SizeTier.LARGE);
		assertEquals(h, PromptHistory.fromJson(h.toJson()));
	}

	@Test
	void corruptJsonGivesEmpty() {
		assertEquals(PromptHistory.empty(), PromptHistory.fromJson("{oops"));
		assertEquals(PromptHistory.empty(), PromptHistory.fromJson(null));
		assertEquals(PromptHistory.empty(), PromptHistory.fromJson("[1,2]"));
	}

	@Test
	void badEntriesAreSkipped() {
		PromptHistory h = PromptHistory.fromJson("{\"lastSize\":\"GIANT\",\"recent\":[\"ok\",3,null,\"  \",{\"x\":1}]}");
		assertEquals(List.of("ok"), h.recent());
		assertEquals(SizeTier.AUTO, h.lastSize());
	}

	@Test
	void anEntryThatCantBeSentIsDropped() {
		// A hand-edited file: an entry the server would refuse (or the connection drop) never reaches the screen.
		String atTheLimit = "b".repeat(2000);
		String json = "{\"recent\":[\"" + "a".repeat(2001) + "\",\"  a hut  \",\"\",\"" + atTheLimit + "\"]}";
		assertEquals(List.of("a hut", atTheLimit), PromptHistory.fromJson(json).recent());
	}

	@Test
	void withSizeNullKeepsTheSize() {
		PromptHistory h = PromptHistory.empty().withSize(SizeTier.LARGE).withSize(null);
		assertEquals(SizeTier.LARGE, h.lastSize());
		assertEquals(h, PromptHistory.fromJson(h.toJson()));
	}

	@Test
	void moreThanTenStoredAreCut() {
		String json = "{\"recent\":[" + IntStream.rangeClosed(1, 12).mapToObj(i -> "\"p" + i + "\"").collect(Collectors.joining(",")) + "]}";
		assertEquals(10, PromptHistory.fromJson(json).recent().size());
	}
}
