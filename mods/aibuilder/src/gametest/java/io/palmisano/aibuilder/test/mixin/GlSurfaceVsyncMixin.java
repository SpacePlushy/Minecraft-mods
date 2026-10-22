package io.palmisano.aibuilder.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Gametests only: never wait for vsync. While the Mac's screen is locked or asleep, a vsync'd buffer
 * swap waits for a display refresh that never comes, and the test client freezes on its first frame.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlSurface")
abstract class GlSurfaceVsyncMixin {
	@ModifyArg(method = "configure", at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_SetSwapInterval(I)Z"))
	private int aibuilderTest$neverWaitForVsync(int interval) {
		return 0;
	}
}
