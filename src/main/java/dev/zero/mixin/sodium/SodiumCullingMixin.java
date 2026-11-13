package dev.zero.mixin.sodium;

import dev.zero.modules.XRay;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** X-Ray with Sodium: target blocks draw every face, since the blocks around them are hidden. */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.model.AbstractBlockRenderContext", remap = false)
public abstract class SodiumCullingMixin {
	@Shadow protected BlockState state;
	@Shadow protected BlockPos pos;
	@Shadow protected BlockAndTintGetter level;

	@Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true)
	private void zero$xrayFaces(Direction facing, CallbackInfoReturnable<Boolean> cir) {
		if (!XRay.isActive() || state == null || !XRay.isTarget(state)) return;
		BlockState neighbor = level.getBlockState(pos.relative(facing));
		cir.setReturnValue(neighbor.getBlock() != state.getBlock());
	}
}
