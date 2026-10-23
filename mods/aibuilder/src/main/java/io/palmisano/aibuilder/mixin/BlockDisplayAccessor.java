package io.palmisano.aibuilder.mixin;

import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The block a block display shows, private in 26.3. */
@Mixin(Display.BlockDisplay.class)
public interface BlockDisplayAccessor {
	@Invoker("setBlockState")
	void aibuilder$setBlockState(BlockState state);

	@Invoker("getBlockState")
	BlockState aibuilder$blockState();
}
