package io.palmisano.aibuilder.test;

import java.util.List;
import java.util.UUID;

import io.palmisano.aibuilder.core.frame.Facing;
import io.palmisano.aibuilder.core.frame.Pos;
import io.palmisano.aibuilder.core.request.BuildSession;
import io.palmisano.aibuilder.core.request.SizeTier;
import io.palmisano.aibuilder.core.shapes.Box;
import io.palmisano.aibuilder.world.SessionStore;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

/** Refinement's record (spec §5.4), saved with the world so a refinement works after a restart too. */
public class RefinementGameTest {
	@GameTest
	public void sessionsSurviveASave(GameTestHelper helper) {
		UUID player = UUID.randomUUID();
		BuildSession castle = new BuildSession("minecraft:overworld", "Stonehold Castle", new Pos(100, 64, -7), Facing.WEST, SizeTier.MEDIUM,
				Box.of(-10, -2, 3, 10, 30, 43), "7b0f3c1e-1111-4222-8333-444455556666", List.of("a castle", "add a moat"), 2, true);
		SessionStore store = new SessionStore();
		store.put(player, castle);
		Tag saved = SessionStore.CODEC.encodeStart(NbtOps.INSTANCE, store).getOrThrow();
		SessionStore loaded = SessionStore.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
		helper.assertValueEqual(loaded.get(player).orElseThrow(), castle, "the session after a save");
		helper.assertTrue(loaded.get(UUID.randomUUID()).isEmpty(), "someone else's session");
		helper.succeed();
	}
}
