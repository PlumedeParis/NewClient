package dev.zero.modules;

import dev.zero.module.Category;
import dev.zero.module.ChunkFinderModule;
import dev.zero.render.Render3D;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.util.ColorUtil;
import dev.zero.util.WorldUtil;
import net.minecraft.core.BlockPos;
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
 * Finds bases from amethyst growth. Geodes generate with a uniformly random bud stage
 * (about 25% are full clusters). Buds only grow - and budding amethyst only sprouts new
 * buds - while a player keeps the chunk loaded. A geode that is mostly fully-grown clusters
 * has therefore been loaded for a long time: someone lives or AFKs nearby.
 */
public class AmethystFinder extends ChunkFinderModule<AmethystFinder.Geode> {
	private final NumberSetting minBuds = add(new NumberSetting("Min Buds", "Buds needed to judge a geode", 10, 4, 60, 1)).onChange(this::rescan);
	private final NumberSetting grownRatio = add(new NumberSetting("Grown Ratio", "Share of fully grown clusters to flag (natural is ~25%)", 0.6, 0.35, 1.0, 0.05)).onChange(this::rescan);
	private final BoolSetting showAll = add(new BoolSetting("Show All Geodes", "Also outline geodes that look natural", false));
	private final BoolSetting clusterBoxes = add(new BoolSetting("Cluster Boxes", "Highlight each fully grown cluster", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Tracers to grown geodes", true));

	private static final Set<Block> BUDS = Set.of(Blocks.SMALL_AMETHYST_BUD, Blocks.MEDIUM_AMETHYST_BUD, Blocks.LARGE_AMETHYST_BUD, Blocks.AMETHYST_CLUSTER);

	public record Geode(int small, int medium, int large, int clusters, int budding, AABB bounds, BlockPos center, List<BlockPos> clusterPositions, boolean grown) {
		int buds() { return small + medium + large + clusters; }
		float ratio() { return buds() == 0 ? 0 : clusters / (float) buds(); }
	}

	public AmethystFinder() {
		super("AmethystFinder", "Finds bases from fully grown amethyst geodes", Category.HUNT);
	}

	@Override
	public String getInfo() {
		int n = 0;
		for (Geode g : results.values()) if (g.grown) n++;
		return n > 0 ? String.valueOf(n) : null;
	}

	@Override
	protected boolean relevant(BlockState o, BlockState n) {
		return BUDS.contains(o.getBlock()) || BUDS.contains(n.getBlock()) || n.is(Blocks.BUDDING_AMETHYST) || o.is(Blocks.BUDDING_AMETHYST);
	}

	@Override
	protected Geode analyse(LevelChunk chunk) {
		int small = 0, medium = 0, large = 0, clusters = 0, budding = 0;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		List<BlockPos> clusterPos = new ArrayList<>();
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
						if (b == Blocks.BUDDING_AMETHYST) budding++;
						else if (b == Blocks.SMALL_AMETHYST_BUD) small++;
						else if (b == Blocks.MEDIUM_AMETHYST_BUD) medium++;
						else if (b == Blocks.LARGE_AMETHYST_BUD) large++;
						else if (b == Blocks.AMETHYST_CLUSTER) {
							clusters++;
							if (clusterPos.size() < 200) clusterPos.add(new BlockPos(bx + x, by + y, bz + z));
						} else continue;
						int wx = bx + x, wy = by + y, wz = bz + z;
						minX = Math.min(minX, wx);
						minY = Math.min(minY, wy);
						minZ = Math.min(minZ, wz);
						maxX = Math.max(maxX, wx);
						maxY = Math.max(maxY, wy);
						maxZ = Math.max(maxZ, wz);
					}
		}
		int buds = small + medium + large + clusters;
		if (buds == 0) return null;
		AABB bounds = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1).inflate(0.5);
		BlockPos center = BlockPos.containing(bounds.getCenter());
		boolean grown = buds >= minBuds.getInt() && clusters / (float) buds >= grownRatio.get();
		Geode g = new Geode(small, medium, large, clusters, budding, bounds, center, clusterPos, grown);
		if (grown) report(chunk.getPos(), center, "Grown geode",
			String.format(Locale.ROOT, "%d%% clusters (%d/%d) - loaded for a long time", Math.round(g.ratio() * 100), clusters, buds), 0xFFC9A0FF);
		return g;
	}

	@Override
	public void onRender3D(float pt) {
		Vec3 cam = WorldUtil.cameraPos();
		for (Geode g : results.values()) {
			if (!g.grown && !showAll.get()) continue;
			int color = g.grown ? ColorUtil.lerp(0xFFB98CFF, 0xFFFF4DD8, Math.min(1f, (g.ratio() - 0.5f) * 2)) : 0xFF8A7CA8;
			Render3D.box(g.bounds, ColorUtil.withAlpha(color, g.grown ? 30 : 12), ColorUtil.withAlpha(color, g.grown ? 220 : 120), g.grown ? 2f : 1f, true);
			if (g.grown && clusterBoxes.get()) for (BlockPos p : g.clusterPositions) Render3D.box(new AABB(p).deflate(0.15), ColorUtil.withAlpha(color, 60), ColorUtil.withAlpha(color, 200), 1f, true);
			if (g.grown && tracers.get()) Render3D.tracer(Vec3.atCenterOf(g.center), ColorUtil.withAlpha(color, 200), 1.6f);
			Render3D.label(new Vec3(g.bounds.getCenter().x, g.bounds.maxY + 0.6, g.bounds.getCenter().z),
				(g.grown ? "GROWN " : "geode ") + Math.round(g.ratio() * 100) + "% §7" + g.clusters + "/" + g.buds() + " " + WorldUtil.distance(cam.distanceTo(Vec3.atCenterOf(g.center))), color, 1f);
		}
	}
}
