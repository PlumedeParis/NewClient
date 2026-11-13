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

	private ChunkScanner() {}

	/** Chunks whose blocks changed, waiting to be looked at again (per module). */
	private static final Map<ScanningModule, LongLinkedOpenHashSet> DIRTY = new IdentityHashMap<>();
	/** When each (module, chunk) was last scanned: a chunk that keeps changing is re-scanned at most every few seconds. */
	private static final Map<ScanningModule, it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap> LAST = new IdentityHashMap<>();
	private static final long RESCAN_COOLDOWN_MS = 2500;
	private static int tickCount;

	/**
	 * A block changed in this chunk: re-scan it soon, inside the scanner's time budget
	 * (never immediately, never the same chunk over and over).
	 */
	public static void markDirty(ScanningModule module, long chunkKey) {
		DIRTY.computeIfAbsent(module, k -> new LongLinkedOpenHashSet()).add(chunkKey);
	}

	private static void promoteDirty() {
		long now = System.currentTimeMillis();
		for (Map.Entry<ScanningModule, LongLinkedOpenHashSet> e : DIRTY.entrySet()) {
			LongLinkedOpenHashSet d = e.getValue();
			if (d.isEmpty() || !e.getKey().isEnabled()) continue;
			var last = LAST.get(e.getKey());
			var it = d.iterator();
			while (it.hasNext()) {
				long key = it.nextLong();
				if (last != null && now - last.get(key) < RESCAN_COOLDOWN_MS) continue; // too soon: stays waiting
				if (LOADED.contains(key)) QUEUES.computeIfAbsent(e.getKey(), k -> new LongLinkedOpenHashSet()).add(key);
				it.remove();
			}
		}
	}

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
		for (LongLinkedOpenHashSet d : DIRTY.values()) d.remove(key);
		for (var l : LAST.values()) l.remove(key);
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
		DIRTY.clear();
		LAST.clear();
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
		if (level == null) return;
		if (++tickCount % 5 == 0 && !DIRTY.isEmpty()) promoteDirty();
		if (QUEUES.isEmpty()) return;
		long start = System.nanoTime();
		// Time budget from the Client panel; halved automatically while FPS is low.
		double ms = dev.zero.config.ClientSettings.SCAN_BUDGET.get();
		if (mc.getFps() < 30) ms /= 2;
		long budget = (long) (ms * 1_000_000L);
		boolean worked = true;
		while (worked && System.nanoTime() - start < budget) {
			worked = false;
			for (Map.Entry<ScanningModule, LongLinkedOpenHashSet> e : QUEUES.entrySet()) {
				LongLinkedOpenHashSet q = e.getValue();
				if (q.isEmpty() || !e.getKey().isEnabled()) continue;
				long key = q.removeFirstLong();
				LevelChunk chunk = level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false);
				if (chunk != null) {
					try {
						long ts = System.nanoTime();
						e.getKey().scanChunk(chunk);
						dev.zero.util.Perf.tick("scan:" + e.getKey().getName(), System.nanoTime() - ts);
					} catch (Exception ex) {
						// A chunk changing mid-scan must never crash the game.
					}
					LAST.computeIfAbsent(e.getKey(), k -> new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap()).put(key, System.currentTimeMillis());
				}
				worked = true;
				if (System.nanoTime() - start >= budget) break; // stop mid-round too: one slow scan must not drag the others in
			}
		}
	}
}
