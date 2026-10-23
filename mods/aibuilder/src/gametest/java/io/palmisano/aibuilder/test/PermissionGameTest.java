package io.palmisano.aibuilder.test;

import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.palmisano.aibuilder.BuildManager;
import io.palmisano.aibuilder.BuildPermission;
import io.palmisano.aibuilder.TestHooks;
import io.palmisano.aibuilder.core.config.AiBuilderConfig;
import io.palmisano.aibuilder.core.request.BuildRules;
import io.palmisano.aibuilder.core.request.SizeTier;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSource;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;

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

	/** Someone who may not build doesn't see what a build is doing either (M6 review). */
	@SuppressWarnings("removal")
	@GameTest
	public void strangersDontSeeTheStatus(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		helper.assertValueEqual(BuildManager.of(helper.getLevel().getServer()).status(player), BuildManager.Outcome.REFUSED, "/aibuild status");
		helper.succeed();
	}

	/**
	 * On a server where two players may build, a build is its owner's to follow and to stop: the other is told whose it
	 * is, and it goes on (M6 review; final review). In a test environment of its own, so its build never overlaps the
	 * other tests here, which ask the same manager whether anything is running.
	 */
	@SuppressWarnings("removal")
	@GameTest(environment = "aibuilder-test:alone", maxTicks = 400)
	public void onlyTheBuildsOwnerStopsItOrSeesItsStatus(GameTestHelper helper) {
		ServerPlayer owner = helper.makeMockServerPlayerInLevel();
		ServerPlayer other = helper.makeMockServerPlayerInLevel();
		BuildManager manager = BuildManager.of(helper.getLevel().getServer());
		TestHooks.config = AiBuilderConfig.load("{\"allowed_players\": [\"" + owner.getUUID() + "\", \"" + other.getUUID() + "\"],"
				+ " \"require_op\": false}").config();
		try {
			helper.assertValueEqual(manager.startTest(owner), BuildManager.Outcome.STARTED, "the owner's demo build");
			helper.assertValueEqual(manager.status(other), BuildManager.Outcome.REFUSED, "someone else's status");
			helper.assertValueEqual(manager.status(owner), BuildManager.Outcome.DONE, "the owner's status");
			helper.assertValueEqual(manager.cancel(other), BuildManager.Outcome.REFUSED, "someone else's cancel");
			helper.assertTrue(manager.busy(), "someone else's cancel stopped the build");
			helper.assertValueEqual(manager.cancel(owner), BuildManager.Outcome.DONE, "the owner's cancel");
		} finally {
			// A check above that fails mustn't leave the demo running into the next batch.
			if (manager.busy()) {
				manager.cancel(owner);
			}
			TestHooks.config = null;
		}
		helper.succeedWhen(() -> helper.assertFalse(manager.busy(), "the owner's cancel didn't stop the build"));
	}

	/**
	 * /aibuild cancel while an undo runs says so: an undo can't be stopped, but there is something running (the minors'
	 * review). In a test environment of its own, as it builds and undoes through the shared manager.
	 */
	@SuppressWarnings("removal")
	@GameTest(environment = "aibuilder-test:alone_undo", maxTicks = 2400)
	public void aCancelDuringAnUndoSaysTheUndoIsRunning(GameTestHelper helper) {
		ServerPlayer owner = helper.makeMockServerPlayerInLevel();
		BuildManager manager = BuildManager.of(helper.getLevel().getServer());
		AiBuilderConfig allowed = AiBuilderConfig.load("{\"allowed_players\": [\"" + owner.getUUID() + "\"], \"require_op\": false}").config();
		TestHooks.config = allowed;
		try {
			helper.assertValueEqual(manager.startTest(owner), BuildManager.Outcome.STARTED, "the demo build");
		} finally {
			TestHooks.config = null;
		}
		helper.startSequence()
				.thenWaitUntil(() -> helper.assertFalse(manager.busy(), "the demo is still building"))
				.thenExecute(() -> {
					TestHooks.config = allowed;
					try {
						helper.assertValueEqual(manager.undo(owner), BuildManager.Outcome.STARTED, "the undo");
						helper.assertValueEqual(manager.cancel(owner), BuildManager.Outcome.BUSY, "a cancel while the undo runs");
					} finally {
						TestHooks.config = null;
					}
				})
				.thenWaitUntil(() -> helper.assertFalse(manager.busy(), "the undo never finished"))
				.thenSucceed();
	}

	/**
	 * While Claude Code is being checked for a build, the build is its owner's too: another allowed player's cancel is
	 * refused and the check goes on; the owner's stops it (the minors' review). In a test environment of its own.
	 */
	@SuppressWarnings("removal")
	@GameTest(environment = "aibuilder-test:alone_check", maxTicks = 400)
	public void onlyTheOwnerStopsTheClaudeCodeCheck(GameTestHelper helper) {
		ServerPlayer owner = helper.makeMockServerPlayerInLevel();
		ServerPlayer other = helper.makeMockServerPlayerInLevel();
		BuildManager manager = BuildManager.of(helper.getLevel().getServer());
		TestHooks.config = AiBuilderConfig.load("{\"allowed_players\": [\"" + owner.getUUID() + "\", \"" + other.getUUID() + "\"],"
				+ " \"require_op\": false}").config();
		try {
			manager.submit(owner, "a hut", SizeTier.SMALL);
			helper.assertTrue(manager.busy(), "the Claude Code check didn't start");
			helper.assertValueEqual(manager.cancel(other), BuildManager.Outcome.REFUSED, "someone else's cancel during the check");
			helper.assertTrue(manager.busy(), "someone else's cancel stopped the check");
			helper.assertValueEqual(manager.cancel(owner), BuildManager.Outcome.DONE, "the owner's cancel during the check");
		} finally {
			if (manager.busy()) {
				manager.cancel(owner);
			}
			TestHooks.config = null;
		}
		helper.succeedWhen(() -> helper.assertFalse(manager.busy(), "the owner's cancel didn't stop the check"));
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

	/**
	 * A server's rules (spec §15): only allowed_players, with op_level while require_op. For anyone else nothing
	 * starts, not even the Claude Code check (M6 Review Focus).
	 */
	@SuppressWarnings("removal")
	@GameTest
	public void strangersAreRefusedBeforeAnyCheck(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		MinecraftServer server = helper.getLevel().getServer();
		helper.assertValueEqual(BuildPermission.verdict(player, AiBuilderConfig.DEFAULT), BuildRules.Verdict.NOT_LISTED, "nobody listed");
		AiBuilderConfig listed = AiBuilderConfig.load("{\"allowed_players\": [\"" + player.getGameProfile().name() + "\"]}").config();
		helper.assertValueEqual(BuildPermission.verdict(player, listed), BuildRules.Verdict.NEEDS_OP, "listed, not an op");
		// Level 2, the config's default op_level (a test server's own default for ops is lower).
		server.getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.of(false));
		try {
			helper.assertValueEqual(BuildPermission.verdict(player, listed), BuildRules.Verdict.ALLOWED, "listed and an op");
		} finally {
			server.getPlayerList().deop(player.nameAndId());
		}
		BuildManager manager = BuildManager.of(server);
		manager.submit(player, "a house", SizeTier.SMALL);
		helper.assertFalse(manager.busy(), "a stranger's prompt started something");
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
