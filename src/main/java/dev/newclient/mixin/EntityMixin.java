package dev.newclient.mixin;

import dev.newclient.module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: mouse look rotates the camera instead of the player (no rotation packets). */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "turn", at = @At("HEAD"), cancellable = true)
	private void newclient$turnCamera(double dx, double dy, CallbackInfo ci) {
		if ((Object) this == Minecraft.getInstance().player && ModuleManager.freecam != null && ModuleManager.freecam.active()) {
			ModuleManager.freecam.turn(dx, dy);
			ci.cancel();
		}
	}
}
