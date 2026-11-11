package dev.zero.module;

import net.minecraft.core.BlockPos;

import java.util.List;

/** One piece of base evidence reported by a Hunt module to BaseRadar. */
public record HuntTarget(BlockPos pos, float score, String reason, int color) {
	/** Implemented by modules that can contribute evidence. */
	public interface Provider {
		void collectTargets(List<HuntTarget> out);
	}
}
