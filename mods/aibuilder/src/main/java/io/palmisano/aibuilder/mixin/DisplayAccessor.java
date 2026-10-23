package io.palmisano.aibuilder.mixin;

import com.mojang.math.Transformation;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A display's pose and how far it's drawn: what {@code /data merge entity} sets, private in 26.3. */
@Mixin(Display.class)
public interface DisplayAccessor {
	@Invoker("setTransformation")
	void aibuilder$setTransformation(Transformation transformation);

	/** Ticks the client glides from the pose it shows to a new one. */
	@Invoker("setTransformationInterpolationDuration")
	void aibuilder$setTransformationInterpolationDuration(int ticks);

	/** Ticks until the glide starts; setting it starts the glide again even when the value is the same. */
	@Invoker("setTransformationInterpolationDelay")
	void aibuilder$setTransformationInterpolationDelay(int ticks);

	/** How far it's drawn, in units of 64 blocks (scaled by the player's entity distance). */
	@Invoker("setViewRange")
	void aibuilder$setViewRange(float range);

	@Invoker("getViewRange")
	float aibuilder$viewRange();

	@Invoker("getTransformationInterpolationDuration")
	int aibuilder$transformationInterpolationDuration();

	/** The pose a display draws with (scale and right rotation included), read from its synced data. */
	@Invoker("createTransformation")
	static Transformation aibuilder$transformation(SynchedEntityData data) {
		throw new AssertionError();
	}
}
