package dev.zero.scan;

import dev.zero.module.Module;
import dev.zero.module.ModuleManager;
import dev.zero.module.ScanningModule;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Keeps track of the chunks the client has loaded and feeds them to the scanning modules,
 * nearest first, with a per-tick time budget.
 */
public final class ChunkScanner {
	private static final LongOpenHashSet LOADED = new LongOpenHashSet();
	private static final Map<ScanningModule, LongLinkedOpenHashSet> QUEUES = new IdentityHashMap<>();
	private static final long BUDGET_NANOS = 4_000_000L; // 4 ms per tick max

	private ChunkScanner() {}

	public static void onChunkLoad(ClientLevel level, LevelChunk chunk) {
		long key = chunk.getPos().toLong();
		LOADED.add(key);
		for (Module m : ModuleManager.all()) {
			if (m instanceof ScanningModule s && s.isEnabled()) QUEUES.computeIfAbsent(s, k -> new LongLinkedOpenHashSet()).add(key);
		}
	}

	public static void onChunkUnload(ClientLevel level, LevelChunk chunk) {
		ChunkPos pos = chunk.getPos();
		long key = pos.toLong();
		LOADED.remove(key);
		for (Map.Entry<ScanningModule, LongLinkedOpenHashSet> e : QUEUES.entrySet()) e.getValue().remove(key);
		for (Module m : ModuleManager.all()) {
			if (m instanceof ScanningModule s && s.isEnabled()) s.onChunkUnload(pos);
		}
	}

	/** Called by the ClientLevel mixin when the server updates one block. */
	public static void onBlockUpdate(BlockPos pos, BlockState oldState, BlockState newState) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null || oldState == newState) return;
		LevelChunk chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
		if (chunk == null) return;
		for (Module m : ModuleManager.all()) {
			if (m instanceof ScanningModule s && s.isEnabled()) s.onBlockChanged(chunk, pos, oldState, newState);
		}
	}

	public static void onWorldChange() {
		LOADED.clear();
		QUEUES.clear();
		for (Module m : ModuleManager.all()) m.onWorldLeave();
	}

	/** Queue every loaded chunk for the module, sorted nearest first. */
	public static void rescan(ScanningModule module) {
		Minecraft mc = Minecraft.getInstance();
		LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
		if (mc.player != null) {
			int px = mc.player.chunkPosition().x, pz = mc.player.chunkPosition().z;
			LongArrayList list = new LongArrayList(LOADED);
			list.sort((a, b) -> Integer.compare(dist(a, px, pz), dist(b, px, pz)));
			queue.addAll(list);
		}
		QUEUES.put(module, queue);
	}

	public static void cancel(ScanningModule module) {
		QUEUES.remove(module);
	}

	private static int dist(long key, int px, int pz) {
		int dx = ChunkPos.getX(key) - px, dz = ChunkPos.getZ(key) - pz;
		return dx * dx + dz * dz;
	}

	public static int pending() {
		int n = 0;
		for (LongLinkedOpenHashSet q : QUEUES.values()) n += q.size();
		return n;
	}

	public static int loadedCount() { return LOADED.size(); }

	public static void tick() {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || QUEUES.isEmpty()) return;
		long start = System.nanoTime();
		boolean worked = true;
		while (worked && System.nanoTime() - start < BUDGET_NANOS) {
			worked = false;
			for (Map.Entry<ScanningModule, LongLinkedOpenHashSet> e : QUEUES.entrySet()) {
				LongLinkedOpenHashSet q = e.getValue();
				if (q.isEmpty() || !e.getKey().isEnabled()) continue;
				long key = q.removeFirstLong();
				LevelChunk chunk = level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false);
				if (chunk != null) {
					try {
						e.getKey().scanChunk(chunk);
					} catch (Exception ex) {
						// A chunk changing mid-scan must never crash the game.
					}
				}
				worked = true;
			}
		}
	}
}
