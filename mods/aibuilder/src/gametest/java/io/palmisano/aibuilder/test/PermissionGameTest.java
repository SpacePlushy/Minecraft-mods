package io.palmisano.aibuilder.test;

import io.palmisano.aibuilder.BuildManager;
import io.palmisano.aibuilder.BuildPermission;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/**
 * Builds run on the world owner's own Claude login (spec §15). A test server has no singleplayer owner, so every
 * mock player is someone else: nothing they ask for may start.
 */
public class PermissionGameTest {
	// makeMockServerPlayerInLevel is marked for removal, but it's the only mock with a connection, which the
	// refusal's chat reply needs.
	@SuppressWarnings("removal")
	@GameTest
	public void aNonOwnerIsRefused(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		helper.assertFalse(BuildPermission.canBuild(player), "a mock player counts as the world's owner");
		BuildManager manager = BuildManager.of(helper.getLevel().getServer());
		helper.assertValueEqual(manager.startTest(player), BuildManager.Outcome.REFUSED, "/aibuild test");
		helper.assertValueEqual(manager.cancel(player), BuildManager.Outcome.REFUSED, "/aibuild cancel");
		helper.assertValueEqual(manager.undo(player), BuildManager.Outcome.REFUSED, "/aibuild undo");
		// The other gametests' jobs share the scheduler, so ask the manager rather than count jobs.
		helper.assertFalse(manager.busy(), "something started for a non-owner");
		helper.succeed();
	}
}
