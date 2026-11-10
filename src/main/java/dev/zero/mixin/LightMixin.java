package dev.zero.mixin;

import dev.zero.modules.XRay;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** X-Ray fullbright: ores deep underground are drawn fully lit. */
@Mixin(LevelRenderer.class)
public abstract class LightMixin {
	@Inject(method = "getLightColor(Lnet/minecraft/client/renderer/LevelRenderer$BrightnessGetter;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
		at = @At("HEAD"), cancellable = true)
	private static void zero$fullbright(LevelRenderer.BrightnessGetter getter, BlockAndTintGetter level, BlockState state, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
		if (XRay.fullbright()) cir.setReturnValue(0xF000F0);
	}
}
