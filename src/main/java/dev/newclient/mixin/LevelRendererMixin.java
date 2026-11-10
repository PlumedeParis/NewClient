package dev.newclient.mixin;

import dev.newclient.module.ModuleManager;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Freecam: treat the camera like a spectator's so terrain isn't culled when it is inside blocks. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/LevelRenderer;cullTerrain(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;Z)V"), index = 2)
	private boolean newclient$spectatorCulling(boolean spectator) {
		return spectator || (ModuleManager.freecam != null && ModuleManager.freecam.active());
	}
}
