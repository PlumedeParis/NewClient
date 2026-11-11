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
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * World generation always creates deepslate standing upright (axis Y). Deepslate lying on
 * its side (axis X / Z) was placed by a player - typically to patch a tunnel or hide a base
 * entrance. Also optionally flags cobbled deepslate outside ancient cities.
 */
public class RotatedDeepslate extends ChunkFinderModule<RotatedDeepslate.Found> implements HuntTarget.Provider {
	private final BoolSetting cobbled = add(new BoolSetting("Cobbled Deepslate", "Also flag cobbled deepslate (player-made)", true)).onChange(this::rescan);
	private final NumberSetting alertCount = add(new NumberSetting("Alert Count", "Blocks in a chunk to send an alert", 3, 1, 50, 1));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Tracers to flagged chunks", false));

	public record Found(List<BlockPos> rotated, List<BlockPos> cobbled) {
		int total() { return rotated.size() + cobbled.size(); }
	}

	public RotatedDeepslate() {
		super("RotatedDeepslate", "Deepslate placed sideways = player-placed (DonutSMP trick)", Category.HUNT);
	}

	@Override
	public String getInfo() {
		int n = 0;
		for (Found f : results.values()) n += f.total();
		return n > 0 ? String.valueOf(n) : null;
	}

	@Override
	protected boolean relevant(BlockState o, BlockState n) { return o.is(Blocks.DEEPSLATE) || n.is(Blocks.DEEPSLATE) || o.is(Blocks.COBBLED_DEEPSLATE) || n.is(Blocks.COBBLED_DEEPSLATE); }

	@Override
	protected Found analyse(LevelChunk chunk) {
		List<BlockPos> rotated = new ArrayList<>(), cob = new ArrayList<>();
		boolean ancientCity = false;
		boolean wantCobbled = cobbled.get();
		LevelChunkSection[] sections = chunk.getSections();
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s.hasOnlyAir()) continue;
			boolean maybeRotated = s.getStates().maybeHas(st -> st.is(Blocks.DEEPSLATE) && st.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y);
			boolean maybeCobbled = wantCobbled && s.getStates().maybeHas(st -> st.is(Blocks.COBBLED_DEEPSLATE));
			if (s.getStates().maybeHas(st -> st.is(Blocks.REINFORCED_DEEPSLATE) || st.is(Blocks.SCULK_SHRIEKER))) ancientCity = true;
			if (!maybeRotated && !maybeCobbled) continue;
			int by = chunk.getSectionYFromSectionIndex(i) << 4;
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) {
						BlockState st = s.getBlockState(x, y, z);
						if (st.is(Blocks.DEEPSLATE) && st.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y) {
							if (rotated.size() < 512) rotated.add(new BlockPos(bx + x, by + y, bz + z));
						} else if (maybeCobbled && st.is(Blocks.COBBLED_DEEPSLATE) && cob.size() < 512) {
							cob.add(new BlockPos(bx + x, by + y, bz + z));
						}
					}
		}
		if (ancientCity) cob.clear();
		if (rotated.isEmpty() && cob.isEmpty()) return null;
		Found f = new Found(rotated, cob);
		if (f.total() >= alertCount.getInt()) {
			BlockPos at = !rotated.isEmpty() ? rotated.get(0) : cob.get(0);
			report(chunk.getPos(), at, "Placed deepslate", rotated.size() + " rotated, " + cob.size() + " cobbled", 0xFF60A5FA);
		}
		return f;
	}

	@Override
	public void onRender3D(float pt) {
		Vec3 cam = WorldUtil.cameraPos();
		for (Found f : results.values()) {
			for (BlockPos p : f.rotated()) {
				if (!mc.level.getBlockState(p).is(Blocks.DEEPSLATE)) continue;
				AABB b = new AABB(p).deflate(0.01);
				Render3D.box(b, 0x403B82F6, 0xFF60A5FA, 1.6f, true);
				if (Render3D.look(b)) Render3D.lookText("Deepslate placed sideways \u00B7 player-made", 0xFF60A5FA);
			}
			for (BlockPos p : f.cobbled()) {
				if (!mc.level.getBlockState(p).is(Blocks.COBBLED_DEEPSLATE)) continue;
				AABB b = new AABB(p).deflate(0.01);
				Render3D.box(b, 0x30A5B4FC, 0xC0A5B4FC, 1.2f, true);
				if (Render3D.look(b)) Render3D.lookText("Cobbled Deepslate \u00B7 player-made", 0xFFA5B4FC);
			}
			BlockPos first = !f.rotated().isEmpty() ? f.rotated().get(0) : f.cobbled().get(0);
			if (tracers.get()) Render3D.tracer(Vec3.atCenterOf(first), 0xD060A5FA, 1.5f);
			if (f.total() >= alertCount.getInt())
				Render3D.label(Vec3.atCenterOf(first).add(0, 1.2, 0), "PLACED DEEPSLATE " + f.total() + " §7" + WorldUtil.distance(cam.distanceTo(Vec3.atCenterOf(first))), 0xFF60A5FA, 1f);
		}
	}

	@Override
	public void collectTargets(List<HuntTarget> out) {
		for (Found f : results.values()) {
			BlockPos first = !f.rotated().isEmpty() ? f.rotated().get(0) : f.cobbled().get(0);
			out.add(new HuntTarget(first, Math.min(70, 25 + f.rotated().size() * 8 + f.cobbled().size() * 2), "Placed deepslate x" + f.total(), 0xFF60A5FA));
		}
	}

}
