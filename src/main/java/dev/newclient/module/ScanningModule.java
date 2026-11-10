package dev.newclient.module;

import dev.newclient.scan.ChunkScanner;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * A module that analyses the chunk data the server already sent to the client.
 * Scanning is spread over ticks by {@link ChunkScanner} with a time budget, so it never stutters.
 */
public abstract class ScanningModule extends Module {
	protected ScanningModule(String name, String description, Category category) {
		super(name, description, category);
	}

	/** Analyse a full chunk. Main thread. */
	public abstract void scanChunk(LevelChunk chunk);

	/** A single block changed (server block update). Main thread. */
	public void onBlockChanged(LevelChunk chunk, BlockPos pos, BlockState oldState, BlockState newState) {}

	/** The chunk left the client's memory. */
	public abstract void onChunkUnload(ChunkPos pos);

	/** Forget everything (world change / disable). */
	public abstract void clearAll();

	@Override
	protected void onEnable() {
		ChunkScanner.rescan(this);
	}

	@Override
	protected void onDisable() {
		ChunkScanner.cancel(this);
		clearAll();
	}

	@Override
	public void onWorldLeave() {
		clearAll();
	}

	/** Re-run the scan on every loaded chunk, e.g. after a setting changed. */
	public void rescan() {
		if (!isEnabled()) return;
		clearAll();
		ChunkScanner.rescan(this);
	}
}
