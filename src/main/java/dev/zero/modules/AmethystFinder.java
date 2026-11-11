package dev.zero.modules;

import dev.zero.module.Category;
import dev.zero.module.ChunkFinderModule;
import dev.zero.module.HuntTarget;
import dev.zero.render.Render3D;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.util.ColorUtil;
import dev.zero.util.WorldUtil;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds bases from amethyst growth.
 * <p>
 * World generation gives every bud a random stage, so only ~25% are fully grown clusters.
 * Buds only grow - and budding amethyst only sprouts new buds - while a player keeps the
 * chunk loaded. A geode where almost every bud is a full cluster has been loaded for a very
 * long time: a base is close and in use. Only those geodes are highlighted.
 * Buds are grouped into whole geodes across chunk borders before judging them.
 */
public class AmethystFinder extends ChunkFinderModule<AmethystFinder.ChunkBuds> implements HuntTarget.Provider {
	private final NumberSetting minBuds = add(new NumberSetting("Min Buds", "Buds a geode needs before it is judged", 8, 4, 60, 1)).onChange(this::markChanged);
	private final NumberSetting grownRatio = add(new NumberSetting("Grown Ratio", "Share of fully grown clusters to flag (natural is ~25%)", 0.8, 0.5, 1.0, 0.05)).onChange(this::markChanged);
	private final BoolSetting countLarge = add(new BoolSetting("Count Large Buds", "Treat large buds as almost grown (more sensitive)", false)).onChange(this::markChanged);
	private final BoolSetting clusterBoxes = add(new BoolSetting("Cluster Boxes", "Highlight each fully grown cluster of flagged geodes", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Tracers to flagged geodes", true));

	private static final Set<Block> BUDS = Set.of(Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD, Blocks.AMETHYST_CLUSTER);

	/** Raw bud data of one chunk: packed positions and stages (0 small .. 3 cluster, 4 budding block). */
	public record ChunkBuds(long[] positions, byte[] stages) {}

	/** A whole geode (possibly spanning several chunks). */
	public record Geode(int small, int medium, int large, int clusters, AABB bounds, BlockPos center, List<BlockPos> clusterPositions, float ratio, double naturalOdds) {
		int buds() { return small + medium + large + clusters; }
	}

	private final List<Geode> flagged = new ArrayList<>();
	private boolean changed;

	public AmethystFinder() {
		super("AmethystFinder", "Highlights geodes grown by long chunk loading (a base is near)", Category.HUNT);
	}

	private void markChanged() { changed = true; }

	@Override
	protected void resultsChanged() { changed = true; }

	@Override
	public String getInfo() { return flagged.isEmpty() ? null : String.valueOf(flagged.size()); }

	@Override
	protected boolean relevant(BlockState o, BlockState n) {
		return BUDS.contains(o.getBlock()) || BUDS.contains(n.getBlock()) || n.is(Blocks.BUDDING_AMETHYST) || o.is(Blocks.BUDDING_AMETHYST);
	}

	@Override
	protected ChunkBuds analyse(LevelChunk chunk) {
		List<Long> pos = new ArrayList<>();
		List<Byte> stage = new ArrayList<>();
		LevelChunkSection[] sections = chunk.getSections();
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s.hasOnlyAir() || !s.getStates().maybeHas(st -> st.is(Blocks.BUDDING_AMETHYST) || BUDS.contains(st.getBlock()))) continue;
			int by = chunk.getSectionYFromSectionIndex(i) << 4;
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) {
						Block b = s.getBlockState(x, y, z).getBlock();
						byte st;
						if (b == Blocks.SMALL_AMETHYST_BUD) st = 0;
						else if (b == Blocks.MEDIUM_AMETHYST_BUD) st = 1;
						else if (b == Blocks.LARGE_AMETHYST_BUD) st = 2;
						else if (b == Blocks.AMETHYST_CLUSTER) st = 3;
						else if (b == Blocks.BUDDING_AMETHYST) st = 4;
						else continue;
						pos.add(BlockPos.asLong(bx + x, by + y, bz + z));
						stage.add(st);
					}
		}
		if (pos.isEmpty()) return null;
		long[] p = new long[pos.size()];
		byte[] s = new byte[pos.size()];
		for (int i = 0; i < p.length; i++) {
			p[i] = pos.get(i);
			s[i] = stage.get(i);
		}
		return new ChunkBuds(p, s);
	}

	@Override
	public void onTick() {
		super.onTick();
		if (changed) {
			changed = false;
			rebuild();
		}
	}

	/** Groups all buds into geodes (points within 3 blocks belong together) and judges each one. */
	private void rebuild() {
		List<long[]> pts = new ArrayList<>();
		List<Byte> stages = new ArrayList<>();
		for (ChunkBuds cb : results.values()) {
			for (int i = 0; i < cb.positions().length; i++) {
				pts.add(new long[] {cb.positions()[i]});
				stages.add(cb.stages()[i]);
			}
		}
		int n = pts.size();
		int[] parent = new int[n];
		for (int i = 0; i < n; i++) parent[i] = i;
		// Spatial hash with 4-block cells; neighbouring cells are joined.
		Long2IntOpenHashMap cellFirst = new Long2IntOpenHashMap();
		cellFirst.defaultReturnValue(-1);
		long[] cellOf = new long[n];
		for (int i = 0; i < n; i++) {
			BlockPos p = BlockPos.of(pts.get(i)[0]);
			long cell = BlockPos.asLong(p.getX() >> 2, p.getY() >> 2, p.getZ() >> 2);
			cellOf[i] = cell;
			int first = cellFirst.get(cell);
			if (first < 0) cellFirst.put(cell, i);
			else union(parent, i, first);
		}
		for (var e : cellFirst.long2IntEntrySet()) {
			BlockPos c = BlockPos.of(e.getLongKey());
			for (int dx = -1; dx <= 1; dx++)
				for (int dy = -1; dy <= 1; dy++)
					for (int dz = -1; dz <= 1; dz++) {
						int other = cellFirst.get(BlockPos.asLong(c.getX() + dx, c.getY() + dy, c.getZ() + dz));
						if (other >= 0) union(parent, e.getIntValue(), other);
					}
		}
		java.util.Map<Integer, List<Integer>> groups = new java.util.HashMap<>();
		for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);

		flagged.clear();
		for (List<Integer> members : groups.values()) {
			int[] count = new int[5];
			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
			List<BlockPos> clusters = new ArrayList<>();
			for (int i : members) {
				BlockPos p = BlockPos.of(pts.get(i)[0]);
				byte st = stages.get(i);
				count[st]++;
				if (st == 3 && clusters.size() < 300) clusters.add(p);
				minX = Math.min(minX, p.getX());
				minY = Math.min(minY, p.getY());
				minZ = Math.min(minZ, p.getZ());
				maxX = Math.max(maxX, p.getX());
				maxY = Math.max(maxY, p.getY());
				maxZ = Math.max(maxZ, p.getZ());
			}
			int buds = count[0] + count[1] + count[2] + count[3];
			if (buds < minBuds.getInt()) continue;
			int grown = count[3] + (countLarge.get() ? count[2] : 0);
			float ratio = grown / (float) buds;
			if (ratio < grownRatio.get()) continue;
			double natural = binomialTail(buds, grown, countLarge.get() ? 0.5 : 0.25);
			AABB bounds = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1).inflate(0.5);
			BlockPos center = BlockPos.containing(bounds.getCenter());
			Geode g = new Geode(count[0], count[1], count[2], count[3], bounds, center, clusters, ratio, natural);
			flagged.add(g);
			report(new ChunkPos(center), center, "Grown geode",
				String.format(Locale.ROOT, "%d%% fully grown (%d/%d buds), natural odds %s - base nearby", Math.round(ratio * 100), grown, buds, odds(natural)), 0xFFC9A0FF);
		}
	}

	private static int find(int[] p, int i) {
		while (p[i] != i) {
			p[i] = p[p[i]];
			i = p[i];
		}
		return i;
	}

	private static void union(int[] p, int a, int b) {
		int ra = find(p, a), rb = find(p, b);
		if (ra != rb) p[ra] = rb;
	}

	/** P(X >= k) for X ~ Binomial(n, p): how likely this much growth is without any player. */
	static double binomialTail(int n, int k, double p) {
		double sum = 0;
		for (int i = k; i <= n; i++) sum += Math.exp(logChoose(n, i) + i * Math.log(p) + (n - i) * Math.log(1 - p));
		return Math.min(1, sum);
	}

	private static double logChoose(int n, int k) {
		double r = 0;
		for (int i = 1; i <= k; i++) r += Math.log(n - k + i) - Math.log(i);
		return r;
	}

	static String odds(double p) {
		if (p <= 0) return "~0";
		if (p >= 0.01) return String.format(Locale.ROOT, "%.0f%%", p * 100);
		double inv = 1 / p;
		if (inv >= 1e9) return "<1 in a billion";
		if (inv >= 1e6) return String.format(Locale.ROOT, "1 in %.0fM", inv / 1e6);
		if (inv >= 1e3) return String.format(Locale.ROOT, "1 in %.0fk", inv / 1e3);
		return String.format(Locale.ROOT, "1 in %.0f", inv);
	}

	@Override
	public void collectTargets(List<HuntTarget> out) {
		for (Geode g : flagged)
			out.add(new HuntTarget(g.center(), 45 + 40 * Math.min(1f, (g.ratio() - 0.5f) * 2), "Grown geode " + Math.round(g.ratio() * 100) + "%", 0xFFC9A0FF));
	}

	@Override
	public void onRender3D(float pt) {
		Vec3 cam = WorldUtil.cameraPos();
		for (Geode g : flagged) {
			int color = ColorUtil.lerp(0xFFB98CFF, 0xFFFF4DD8, Math.min(1f, (g.ratio() - 0.5f) * 2));
			Render3D.box(g.bounds(), ColorUtil.withAlpha(color, 30), ColorUtil.withAlpha(color, 230), 2f, true);
			if (clusterBoxes.get()) for (BlockPos p : g.clusterPositions()) {
				if (!mc.level.getBlockState(p).is(Blocks.AMETHYST_CLUSTER)) continue;
				AABB cb = new AABB(p).deflate(0.15);
				Render3D.box(cb, ColorUtil.withAlpha(color, 55), ColorUtil.withAlpha(color, 190), 1f, true);
				if (Render3D.look(cb)) Render3D.lookText("Amethyst Cluster \u00B7 geode " + Math.round(g.ratio() * 100) + "% grown", color);
			}
			if (tracers.get()) Render3D.tracer(Vec3.atCenterOf(g.center()), ColorUtil.withAlpha(color, 200), 1.6f);
			Render3D.label(new Vec3(g.bounds().getCenter().x, g.bounds().maxY + 0.6, g.bounds().getCenter().z),
				"GROWN GEODE " + Math.round(g.ratio() * 100) + "% §7" + g.clusters() + "/" + g.buds() + " • natural " + odds(g.naturalOdds()) + " • "
					+ WorldUtil.distance(cam.distanceTo(Vec3.atCenterOf(g.center()))), color, 1f);
		}
	}
}
