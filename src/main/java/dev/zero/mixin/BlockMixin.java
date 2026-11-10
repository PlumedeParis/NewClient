package dev.zero.mixin;

import dev.zero.modules.XRay;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** X-Ray: target blocks draw every face, since their neighbours are hidden. */
@Mixin(Block.class)
public abstract class BlockMixin {
	@Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
	private static void zero$xrayFaces(BlockState state, BlockState neighbor, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		if (XRay.isActive() && XRay.isTarget(state)) cir.setReturnValue(!XRay.isTarget(neighbor) || neighbor.getBlock() != state.getBlock());
	}
}
