package dev.newclient.mixin;

import dev.newclient.module.ModuleManager;
import dev.newclient.modules.Freecam;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: overrides only where the camera renders from. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow private boolean detached;

	@Shadow protected abstract void setRotation(float yRot, float xRot);

	@Shadow protected abstract void setPosition(Vec3 pos);

	@Inject(method = "setup", at = @At("TAIL"))
	private void newclient$freecam(Level level, Entity entity, boolean detached, boolean mirror, float partialTick, CallbackInfo ci) {
		Freecam freecam = ModuleManager.freecam;
		if (freecam == null || !freecam.active()) return;
		this.setRotation(freecam.getYaw(partialTick), freecam.getPitch(partialTick));
		this.setPosition(freecam.getPos(partialTick));
		this.detached = true; // renders your own player model and hides the first-person hand
	}
}
