package io.palmisano.aibuilder.mixin;

import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** What a dispenser does with an item its registry doesn't name (fires a projectile, equips armour…), private in 26.3. */
@Mixin(DispenserBlock.class)
public interface DispenserBlockInvoker {
	@Invoker("getDefaultDispenseMethod")
	static DispenseItemBehavior aibuilder$defaultDispenseMethod(ItemStack stack) {
		throw new AssertionError();
	}
}
