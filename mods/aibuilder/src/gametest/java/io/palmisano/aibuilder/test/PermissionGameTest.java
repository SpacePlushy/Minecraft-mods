package io.palmisano.aibuilder.test;

import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.palmisano.aibuilder.BuildManager;
import io.palmisano.aibuilder.BuildPermission;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
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

	/**
	 * The owner is the singleplayer profile's UUID, never its name: an offline LAN guest can take the host's name (M2
	 * review). The test server has no owner, so this sets one briefly; the other tests' mock players have other ids.
	 */
	@SuppressWarnings("removal")
	@GameTest
	public void ownerIsComparedById(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		MinecraftServer server = helper.getLevel().getServer();
		GameProfile before = server.getSingleplayerProfile();
		try {
			server.setSingleplayerProfile(new GameProfile(UUID.randomUUID(), player.getGameProfile().name()));
			helper.assertFalse(BuildPermission.canBuild(player), "a player with the owner's name but not their id counts as the owner");
			server.setSingleplayerProfile(new GameProfile(player.getUUID(), "someone else"));
			helper.assertTrue(BuildPermission.canBuild(player), "the owner, by id, isn't the owner");
		} finally {
			server.setSingleplayerProfile(before);
		}
		helper.succeed();
	}

	/** A command block's {@code /execute as <owner> run aibuild …} isn't the owner typing it (M2 review). */
	@SuppressWarnings("removal")
	@GameTest
	public void commandBlocksCantSpeakForTheOwner(GameTestHelper helper) throws CommandSyntaxException {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		var dispatcher = helper.getLevel().getServer().getCommands().getDispatcher();
		int fromSomethingElse = dispatcher.execute("aibuild status", player.createCommandSourceStack().withSource(CommandSource.NULL));
		int typed = dispatcher.execute("aibuild status", player.createCommandSourceStack());
		helper.assertValueEqual(fromSomethingElse, 0, "/execute as the player, run by something else");
		helper.assertValueEqual(typed, 1, "the player's own command");
		helper.succeed();
	}
}
