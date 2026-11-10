package dev.zero.mixin;

import dev.zero.module.ModuleManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Freecam: blocks attack / use / pick-block while the camera is detached, so the client can
 * never send an interaction for something the real player is not looking at.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	private static boolean zero$blocked() {
		return ModuleManager.freecam != null && ModuleManager.freecam.active();
	}

	/** X-Ray: no ambient-occlusion darkening on floating ores. */
	@Inject(method = "useAmbientOcclusion", at = @At("HEAD"), cancellable = true)
	private static void zero$noAo(CallbackInfoReturnable<Boolean> cir) {
		if (dev.zero.modules.XRay.isActive()) cir.setReturnValue(false);
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void zero$noAttack(CallbackInfoReturnable<Boolean> cir) {
		if (zero$blocked()) cir.setReturnValue(false);
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void zero$noContinueAttack(boolean leftClick, CallbackInfo ci) {
		if (zero$blocked()) ci.cancel();
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void zero$noUse(CallbackInfo ci) {
		if (zero$blocked()) ci.cancel();
	}

	@Inject(method = "pickBlock", at = @At("HEAD"), cancellable = true)
	private void zero$noPick(CallbackInfo ci) {
		if (zero$blocked()) ci.cancel();
	}
}
