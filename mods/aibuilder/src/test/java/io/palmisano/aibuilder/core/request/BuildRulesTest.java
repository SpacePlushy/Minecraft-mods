package io.palmisano.aibuilder.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import io.palmisano.aibuilder.core.config.AiBuilderConfig;
import io.palmisano.aibuilder.core.request.BuildRules.Verdict;
import org.junit.jupiter.api.Test;

/** Who may build (spec §15): builds run on one person's own Claude login. */
class BuildRulesTest {
	private static final UUID HOST = UUID.fromString("0f6a1c2e-0000-4000-8000-000000000001");
	private static final UUID GUEST = UUID.fromString("0f6a1c2e-0000-4000-8000-000000000002");

	private static AiBuilderConfig config(String json) {
		return AiBuilderConfig.load(json).config();
	}

	@Test
	void singleplayerOwnerOnly() {
		assertEquals(Verdict.ALLOWED, BuildRules.decide(BuildRules.Server.integrated(HOST), "Frankie", HOST, 0, AiBuilderConfig.DEFAULT));
	}

	/** Opened to LAN, the world is still the host's: a guest is refused whatever the config says. */
	@Test
	void lanGuestsAreRefused() {
		AiBuilderConfig everyone = config("{\"allowed_players\": [\"Guest\"], \"require_op\": false}");
		assertEquals(Verdict.OWNER_ONLY, BuildRules.decide(BuildRules.Server.integrated(HOST), "Guest", GUEST, 4, everyone));
	}

	@Test
	void dedicatedNeedsTheListAndOp() {
		AiBuilderConfig listed = config("{\"allowed_players\": [\"frankie\"]}");
		assertEquals(Verdict.NOT_LISTED, BuildRules.decide(BuildRules.Server.DEDICATED, "Frankie", HOST, 4, AiBuilderConfig.DEFAULT));
		assertEquals(Verdict.NEEDS_OP, BuildRules.decide(BuildRules.Server.DEDICATED, "Frankie", HOST, 1, listed));
		assertEquals(Verdict.ALLOWED, BuildRules.decide(BuildRules.Server.DEDICATED, "Frankie", HOST, 2, listed));
		assertEquals(Verdict.ALLOWED, BuildRules.decide(BuildRules.Server.DEDICATED, "NewName", HOST, 2,
				config("{\"allowed_players\": [\"" + HOST + "\"]}")), "a UUID survives a name change");
		assertEquals(Verdict.NOT_LISTED, BuildRules.decide(BuildRules.Server.DEDICATED, "Guest", GUEST, 4, listed));
	}

	@Test
	void opNotRequiredWhenOff() {
		AiBuilderConfig noOp = config("{\"allowed_players\": [\"Frankie\"], \"require_op\": false}");
		assertEquals(Verdict.ALLOWED, BuildRules.decide(BuildRules.Server.DEDICATED, "Frankie", HOST, 0, noOp));
		assertEquals(Verdict.NEEDS_OP, BuildRules.decide(BuildRules.Server.DEDICATED, "Frankie", HOST, 2,
				config("{\"allowed_players\": [\"Frankie\"], \"op_level\": 3}")));
	}
}
