package io.palmisano.aibuilder.world;

import io.palmisano.aibuilder.core.Tunables;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a block going in sounds and looks like (spec §9's feel): the block's own place sound, softer than a player's,
 * and a small puff of its particles. The placement job plays it at a few blocks a tick, never for air. Server thread.
 */
@FunctionalInterface
public interface PlacementFeel {
	PlacementFeel STANDARD = (level, pos, state) -> {
		SoundType sound = state.getSoundType();
		// A player's placement: volume (v + 1) / 2, pitch p × 0.8 (vanilla's BlockItem); softer here.
		level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1f) / 2f * Tunables.FEEL_VOLUME,
				sound.getPitch() * 0.8f);
		level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
				Tunables.FEEL_PARTICLES, 0.3, 0.3, 0.3, 0.0);
	};

	void placed(ServerLevel level, BlockPos pos, BlockState state);
}
