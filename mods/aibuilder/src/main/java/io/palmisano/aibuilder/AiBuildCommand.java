package io.palmisano.aibuilder;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.palmisano.aibuilder.core.request.SizeTier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** {@code /aibuild <prompt>}. No permission requirement on the node, so a non-owner gets a plain refusal. */
public final class AiBuildCommand {
	private AiBuildCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("aibuild")
				.then(Commands.argument("prompt", StringArgumentType.greedyString())
						.executes(context -> {
							BuildManager.submit(context.getSource().getPlayerOrException(),
									StringArgumentType.getString(context, "prompt"), SizeTier.AUTO);
							return 1;
						})));
	}
}
