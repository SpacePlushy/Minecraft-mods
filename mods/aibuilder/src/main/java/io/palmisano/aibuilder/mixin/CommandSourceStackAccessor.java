package io.palmisano.aibuilder.mixin;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Who really ran a command; 26.3 keeps it private. */
@Mixin(CommandSourceStack.class)
public interface CommandSourceStackAccessor {
	/** The player for a command they typed; a command block (or nothing) behind an {@code /execute as <player>}. */
	@Accessor("source")
	CommandSource aibuilder$source();
}
