package dev.zero.mixin;

import dev.zero.scan.ChunkScanner;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Passively observes block updates the server sends, so scanners stay accurate. */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	@Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"))
	private void zero$onBlockUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
		BlockState old = ((ClientLevel) (Object) this).getBlockState(pos);
		if (old != state) ChunkScanner.onBlockUpdate(pos.immutable(), old, state);
	}
}
