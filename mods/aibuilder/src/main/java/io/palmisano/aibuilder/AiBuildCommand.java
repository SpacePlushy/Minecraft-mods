package io.palmisano.aibuilder;

import java.util.function.BiConsumer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.palmisano.aibuilder.core.request.Messages;
import io.palmisano.aibuilder.core.request.SizeTier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /aibuild <prompt>}, {@code test}, {@code undo}, {@code cancel}, {@code status} and {@code survey}. No permission
 * requirement on the nodes, so a non-owner gets a plain refusal. Brigadier tries only the literal when a word matches
 * one, so a prompt starting with one of those words has to come from the screen.
 */
public final class AiBuildCommand {
	private AiBuildCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("aibuild")
				.then(Commands.literal("test").executes(context -> run(context, BuildManager::startTest)))
				.then(Commands.literal("undo").executes(context -> run(context, BuildManager::undo)))
				.then(Commands.literal("cancel").executes(context -> run(context, BuildManager::cancel)))
				.then(Commands.literal("status").executes(context -> run(context, BuildManager::status)))
				.then(Commands.literal("survey").executes(context -> run(context, BuildManager::survey)))
				// Always a new build in front of the player, even next to the last one (spec §5.2).
				.then(Commands.literal("new").then(Commands.argument("prompt", StringArgumentType.greedyString())
						.executes(context -> run(context,
								(manager, player) -> manager.submit(player, StringArgumentType.getString(context, "prompt"), SizeTier.AUTO, true)))))
				.then(Commands.argument("prompt", StringArgumentType.greedyString())
						.executes(context -> run(context,
								(manager, player) -> manager.submit(player, StringArgumentType.getString(context, "prompt"), SizeTier.AUTO, false)))));
	}

	private static int run(CommandContext<CommandSourceStack> context, BiConsumer<BuildManager, ServerPlayer> action)
			throws CommandSyntaxException {
		ServerPlayer player = context.getSource().getPlayerOrException();
		if (!BuildPermission.ownCommand(context.getSource(), player)) {
			// A command block's /execute as the owner would send someone else's prompt through the owner's login.
			context.getSource().sendFailure(Component.literal(Messages.typeItYourself()));
			return 0;
		}
		action.accept(BuildManager.of(context.getSource().getServer()), player);
		return 1;
	}
}
