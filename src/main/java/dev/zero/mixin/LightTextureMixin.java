package dev.zero.mixin;

import dev.zero.modules.Fullbright;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Fullbright: raises the lightmap's night-vision factor (4th value of the LightmapInfo block). Local rendering only. */
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
	@ModifyArg(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal = 3))
	private float zero$fullbright(float nightVision) {
		return Math.max(nightVision, Fullbright.factor());
	}

	@ModifyArg(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal = 6))
	private float zero$fullbrightGamma(float brightness) {
		return Math.max(brightness, Fullbright.factor());
	}
}
