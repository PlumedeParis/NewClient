package dev.zero.seed;

import dev.zero.ZeroClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.ticks.ProtoChunkTicks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background ore prediction. Terrain of loaded chunks is copied on the main thread; the
 * vanilla ore features then run on a worker thread inside a private sandbox.
 */
public final class OrePredictor {
	public record Result(List<SeedWorld.Prediction> predictions, boolean realTerrain) {}

	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "ZERØ seed predictor");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	private final Map<Long, Result> results = new ConcurrentHashMap<>();
	private final Set<Long> pending = ConcurrentHashMap.newKeySet();
	private final AtomicInteger generation = new AtomicInteger();
	private volatile SeedWorld world;
	private volatile String status = "Enter a seed";
	private long seed;
	private boolean nether;
	private int minY, height;
	private boolean configured;

	public Map<Long, Result> results() { return results; }

	public SeedWorld world() { return world; }

	public String status() { return status; }

	public boolean ready() { return world != null; }

	public int pendingCount() { return pending.size(); }

	/** (Re)configures for a seed + dimension. Builds the worldgen data in the background. */
	public void configure(long seed, boolean nether, int minY, int height) {
		if (configured && seed == this.seed && nether == this.nether && minY == this.minY && height == this.height) return;
		this.seed = seed;
		this.nether = nether;
		this.minY = minY;
		this.height = height;
		this.configured = true;
		reset();
		world = null;
		status = "Building world generation...";
		int gen = generation.get();
		worker.execute(() -> {
			try {
				long t0 = System.currentTimeMillis();
				SeedWorld w = SeedWorld.create(seed, nether, minY, height);
				ZeroClient.LOGGER.info("Seed world ready in {} ms ({} ore features, nether={})", System.currentTimeMillis() - t0, w.tasks.size(), nether);
				if (generation.get() == gen) {
					world = w;
					status = "Ready";
				}
			} catch (Throwable t) {
				ZeroClient.LOGGER.error("Seed world setup failed", t);
				status = "Error: " + t.getClass().getSimpleName();
			}
		});
	}

	public void unconfigure() {
		configured = false;
		world = null;
		reset();
		status = "Enter a seed";
	}

	public void reset() {
		generation.incrementAndGet();
		results.clear();
		pending.clear();
	}

	public void forget(long key) { results.remove(key); }

	public boolean isPending(long key) { return pending.contains(key); }

	/** Main thread: snapshot the 3x3 terrain around a chunk and queue its prediction. */
	public void submit(ClientLevel level, int cx, int cz, Set<Block> wanted) {
		SeedWorld w = world;
		if (w == null) return;
		long key = ChunkPos.asLong(cx, cz);
		if (!pending.add(key)) return;
		PalettedContainerFactory factory = level.palettedContainerFactory();
		int sections = level.getSectionsCount();
		LevelChunkSection[][] copies = new LevelChunkSection[9][];
		int[] surface = new int[48 * 48];
		java.util.Arrays.fill(surface, Integer.MIN_VALUE);
		boolean centerLoaded = false;
		for (int dz = -1; dz <= 1; dz++)
			for (int dx = -1; dx <= 1; dx++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				if (dx == 0 && dz == 0) centerLoaded = true;
				LevelChunkSection[] src = chunk.getSections();
				LevelChunkSection[] dst = new LevelChunkSection[src.length];
				for (int i = 0; i < src.length; i++) dst[i] = src[i].copy();
				copies[(dx + 1) + (dz + 1) * 3] = dst;
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++)
						surface[(dx + 1) * 16 + x + ((dz + 1) * 16 + z) * 48] = oceanFloorAtGeneration(chunk, x, z);
			}
		final boolean realTerrain = centerLoaded;
		final int gen = generation.get();
		final int minY = this.minY, height = this.height;
		final boolean nether = this.nether;
		worker.execute(() -> {
			try {
				if (generation.get() != gen) return;
				LevelHeightAccessor heightAccessor = LevelHeightAccessor.create(minY, height);
				ChunkAccess[] region = new ChunkAccess[9];
				for (int i = 0; i < 9; i++) {
					int dx = i % 3 - 1, dz = i / 3 - 1;
					LevelChunkSection[] secs = copies[i];
					if (secs == null) secs = synthetic(factory, sections, minY, nether);
					for (LevelChunkSection s : secs) w.stripOres(s);
					region[i] = new ProtoChunk(new ChunkPos(cx + dx, cz + dz), UpgradeData.EMPTY, secs, new ProtoChunkTicks<>(), new ProtoChunkTicks<>(),
						heightAccessor, factory, null);
				}
				SimLevel sim = new SimLevel(w.seed, cx, cz, minY, height, region, w.biomeManager, surface);
				w.decorate(sim, cx, cz, minY);
				List<SeedWorld.Prediction> out = new ArrayList<>();
				for (ChunkAccess c : region) w.collect(c, w.allOres, out);
				if (generation.get() == gen) results.put(key, new Result(List.copyOf(out), realTerrain));
			} catch (Throwable t) {
				ZeroClient.LOGGER.warn("Ore prediction failed for chunk {} {}", cx, cz, t);
			} finally {
				pending.remove(key);
			}
		});
	}

	/**
	 * Approximates the OCEAN_FLOOR_WG heightmap as it was when ores were placed: the highest
	 * motion-blocking block, ignoring water and everything added by later generation steps
	 * (trees, huge mushrooms, cactus, bamboo, pumpkins, surface ice) and by players.
	 */
	private static int oceanFloorAtGeneration(LevelChunk chunk, int x, int z) {
		int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(chunk.getPos().getMinBlockX() + x, top, chunk.getPos().getMinBlockZ() + z);
		for (int y = top; y >= chunk.getMinY(); y--) {
			m.setY(y);
			BlockState s = chunk.getBlockState(m);
			if (s.blocksMotion() && !isLateGeneration(s)) return y + 1;
		}
		return chunk.getMinY();
	}

	private static boolean isLateGeneration(BlockState s) {
		return s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(Blocks.ICE) || s.is(Blocks.CACTUS) || s.is(Blocks.BAMBOO)
			|| s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.MUSHROOM_STEM)
			|| s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON) || s.is(Blocks.BEE_NEST) || s.hasBlockEntity();
	}

	/** Terrain stand-in for chunks the client has not loaded: solid stone / deepslate / netherrack. */
	private static LevelChunkSection[] synthetic(PalettedContainerFactory factory, int count, int minY, boolean nether) {
		LevelChunkSection[] secs = new LevelChunkSection[count];
		for (int i = 0; i < count; i++) {
			int y = minY + i * 16;
			BlockState fill;
			if (nether) fill = y < 0 || y >= 120 ? null : Blocks.NETHERRACK.defaultBlockState();
			else fill = y >= 64 ? null : y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
			secs[i] = template(factory, fill);
		}
		return secs;
	}

	private static final Map<BlockState, LevelChunkSection> TEMPLATES = new ConcurrentHashMap<>();
	private static LevelChunkSection AIR_TEMPLATE;

	private static LevelChunkSection template(PalettedContainerFactory factory, BlockState fill) {
		if (fill == null) {
			if (AIR_TEMPLATE == null) AIR_TEMPLATE = new LevelChunkSection(factory);
			return AIR_TEMPLATE.copy();
		}
		LevelChunkSection t = TEMPLATES.computeIfAbsent(fill, f -> {
			LevelChunkSection s = new LevelChunkSection(factory);
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) s.setBlockState(x, y, z, f, false);
			return s;
		});
		return t.copy();
	}

	public static Minecraft mc() { return Minecraft.getInstance(); }
}
