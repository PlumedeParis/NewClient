package dev.zero.modules;

import dev.zero.module.Category;
import dev.zero.module.HuntTarget;
import dev.zero.module.ChunkFinderModule;
import dev.zero.render.Render3D;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.util.ColorUtil;
import dev.zero.util.WorldUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * "Chunk loaded for a long time" detector from plant growth.
 * World generation gives kelp tops age 20-23 (weeping / twisting vines 17-25, so only kelp
 * is a hard proof). Age 24-25 can only be reached by random-tick growth, which happens only
 * while a player keeps the chunk loaded. Many grown kelp tops = a player stayed close by.
 */
public class GrowthFinder extends ChunkFinderModule<GrowthFinder.Growth> implements HuntTarget.Provider {
	private final NumberSetting minGrown = add(new NumberSetting("Min Grown", "Grown kelp tops in a chunk to flag it", 4, 1, 40, 1)).onChange(this::rescan);
	private final BoolSetting maxOnly = add(new BoolSetting("Max Age Only", "Only count fully grown (age 25) kelp", false)).onChange(this::rescan);
	private final BoolSetting highlight = add(new BoolSetting("Highlight Kelp", "Box each grown kelp top", true));
	private final BoolSetting chunkBox = add(new BoolSetting("Chunk Box", "Outline flagged chunks", true));

	public record Growth(int grown, int total, List<BlockPos> tops, int minY, int maxY, BlockPos center) {}

	private static final Set<Block> KELP = Set.of(Blocks.KELP);

	public GrowthFinder() {
		super("GrowthFinder", "Kelp grown past its natural age = chunk loaded for long", Category.HUNT);
	}

	@Override
	protected boolean relevant(BlockState o, BlockState n) { return o.is(Blocks.KELP) || n.is(Blocks.KELP) || o.is(Blocks.KELP_PLANT) || n.is(Blocks.KELP_PLANT); }

	@Override
	protected Growth analyse(LevelChunk chunk) {
		int threshold = maxOnly.get() ? 25 : 24;
		int grown = 0, total = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		long sx = 0, sy = 0, sz = 0;
		List<BlockPos> tops = new ArrayList<>();
		LevelChunkSection[] sections = chunk.getSections();
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s.hasOnlyAir() || !s.getStates().maybeHas(st -> st.is(Blocks.KELP))) continue;
			int by = chunk.getSectionYFromSectionIndex(i) << 4;
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) {
						BlockState st = s.getBlockState(x, y, z);
						if (!st.is(Blocks.KELP)) continue;
						total++;
						if (st.getValue(GrowingPlantHeadBlock.AGE) >= threshold) {
							grown++;
							BlockPos p = new BlockPos(bx + x, by + y, bz + z);
							if (tops.size() < 128) tops.add(p);
							minY = Math.min(minY, p.getY());
							maxY = Math.max(maxY, p.getY());
							sx += p.getX();
							sy += p.getY();
							sz += p.getZ();
						}
					}
		}
		if (grown < minGrown.getInt()) return null;
		BlockPos center = new BlockPos((int) (sx / grown), (int) (sy / grown), (int) (sz / grown));
		Growth g = new Growth(grown, total, tops, minY, maxY, center);
		report(chunk.getPos(), center, "Grown kelp", grown + "/" + total + " kelp past natural age - chunk loaded for long", 0xFF4ADE80);
		return g;
	}

	@Override
	public void onRender3D(float pt) {
		Vec3 cam = WorldUtil.cameraPos();
		for (var e : results.long2ObjectEntrySet()) {
			Growth g = e.getValue();
			int color = ColorUtil.lerp(0xFF7CE38B, 0xFF00FFA2, Math.min(1f, g.grown() / 30f));
			int cx = net.minecraft.world.level.ChunkPos.getX(e.getLongKey()) << 4, cz = net.minecraft.world.level.ChunkPos.getZ(e.getLongKey()) << 4;
			if (chunkBox.get()) Render3D.box(new AABB(cx, g.minY() - 1, cz, cx + 16, g.maxY() + 2, cz + 16), ColorUtil.withAlpha(color, 18), ColorUtil.withAlpha(color, 160), 1.4f, true);
			if (highlight.get()) for (BlockPos p : g.tops()) if (mc.level.getBlockState(p).is(Blocks.KELP)) Render3D.box(new AABB(p).deflate(0.1), ColorUtil.withAlpha(color, 50), ColorUtil.withAlpha(color, 200), 1f, true);
			Render3D.label(new Vec3(cx + 8, g.maxY() + 2.5, cz + 8), "GROWN KELP " + g.grown() + " §7" + WorldUtil.distance(cam.distanceTo(Vec3.atCenterOf(g.center()))), color, 1f);
		}
	}

	@Override
	public void collectTargets(List<HuntTarget> out) {
		for (Growth g : results.values()) out.add(new HuntTarget(g.center(), Math.min(60, 20 + g.grown() * 3), "Grown kelp x" + g.grown(), 0xFF4ADE80));
	}

}
