package dev.zero.mixin.sodium;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.zero.modules.XRay;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * X-Ray with Sodium: the same three decisions as the vanilla mesher (which blocks get a
 * model, whether fluids are drawn, cave occlusion), hooked in Sodium's meshing task.
 * Targets Sodium by name and only uses Minecraft types, so ZERØ builds and runs without it.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask", remap = false)
public abstract class SodiumMeshingMixin {
	@WrapOperation(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;", remap = true))
	private RenderShape zero$xrayBlocks(BlockState state, Operation<RenderShape> original, @Local(ordinal = 0) BlockPos.MutableBlockPos pos) {
		if (XRay.isActive()) {
			XRay.sodiumHook();
			var level = Minecraft.getInstance().level;
			if (level == null || !XRay.shouldRender(state, pos, level)) return RenderShape.INVISIBLE;
		}
		return original.call(state);
	}

	@WrapOperation(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isSolidRender()Z", remap = true))
	private boolean zero$noOcclusion(BlockState state, Operation<Boolean> original) {
		return !XRay.isActive() && original.call(state);
	}

	/** Sodium only meshes a fluid when its FluidState is not empty. */
	@WrapOperation(method = "execute", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;isEmpty()Z", remap = true))
	private boolean zero$xrayFluids(FluidState fluid, Operation<Boolean> original) {
		if (XRay.isActive() && !XRay.renderFluids()) return true;
		return original.call(fluid);
	}
}
