package dev.newclient.mixin;

import dev.newclient.module.ModuleManager;
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
	private static boolean newclient$blocked() {
		return ModuleManager.freecam != null && ModuleManager.freecam.active();
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void newclient$noAttack(CallbackInfoReturnable<Boolean> cir) {
		if (newclient$blocked()) cir.setReturnValue(false);
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void newclient$noContinueAttack(boolean leftClick, CallbackInfo ci) {
		if (newclient$blocked()) ci.cancel();
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void newclient$noUse(CallbackInfo ci) {
		if (newclient$blocked()) ci.cancel();
	}

	@Inject(method = "pickBlock", at = @At("HEAD"), cancellable = true)
	private void newclient$noPick(CallbackInfo ci) {
		if (newclient$blocked()) ci.cancel();
	}
}
