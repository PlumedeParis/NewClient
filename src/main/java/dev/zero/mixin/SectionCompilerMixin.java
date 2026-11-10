package dev.zero.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.zero.modules.XRay;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * X-Ray: hides non-target blocks while chunk meshes are built and disables cave culling.
 * Wrapping getRenderShape() works both with vanilla meshing and with Fabric API's Indigo
 * renderer (which redirects that same call to mesh the model itself).
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
	@WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isSolidRender()Z"))
	private boolean zero$noOcclusion(BlockState state, Operation<Boolean> original) {
		return !XRay.isActive() && original.call(state);
	}

	@WrapOperation(method = "compile", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
	private RenderShape zero$xrayBlocks(BlockState state, Operation<RenderShape> original,
										@Local(argsOnly = true) RenderSectionRegion region, @Local(ordinal = 2) BlockPos pos) {
		if (XRay.isActive() && !XRay.shouldRender(state, pos, region)) return RenderShape.INVISIBLE;
		return original.call(state);
	}

	@WrapWithCondition(method = "compile", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderLiquid(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
	private boolean zero$xrayFluids(BlockRenderDispatcher dispatcher, BlockPos pos, BlockAndTintGetter level, VertexConsumer consumer, BlockState state, FluidState fluid) {
		return !XRay.isActive() || XRay.renderFluids();
	}
}
