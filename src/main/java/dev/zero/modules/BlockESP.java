package dev.zero.modules;

import dev.zero.module.Category;
import dev.zero.module.ScanningModule;
import dev.zero.render.Render3D;
import dev.zero.setting.BlockListSetting;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.ColorSetting;
import dev.zero.setting.EnumSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.util.BlockColors;
import dev.zero.util.ColorUtil;
import dev.zero.util.WorldUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Highlights chosen blocks (ores, spawners, ...) through walls.
 * Reads only the chunk data the server already sent - nothing is requested from the server.
 */
public class BlockESP extends ScanningModule {
	public enum Style { BOTH, OUTLINE, FILL }
	public enum ColorMode { PER_BLOCK, CUSTOM }

	public final BlockListSetting blocks = add(new BlockListSetting("Blocks", "Blocks to highlight", List.of(
		Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS,
		Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE))).onChange(this::rescan);
	private final BoolSetting exposedOnly = add(new BoolSetting("Exposed Only", "Only blocks touching air/water/lava: ignores anti-xray fake ores", false)).onChange(this::rescan);
	private final dev.zero.setting.ButtonSetting colors = add(new dev.zero.setting.ButtonSetting("Block Colors", "Choose the color of each ore / block", "\u25C8 Block Colors \u203A", () -> net.minecraft.client.Minecraft.getInstance().setScreen(new dev.zero.gui.BlockColorScreen(net.minecraft.client.Minecraft.getInstance().screen))));
	private final EnumSetting<Style> style = add(new EnumSetting<>("Style", "How highlighted blocks are drawn", Style.BOTH));
	private final EnumSetting<ColorMode> colorMode = add(new EnumSetting<>("Color Mode", "Per-block palette or one custom color", ColorMode.PER_BLOCK));
	private final ColorSetting color = add(new ColorSetting("Color", "Custom color", 0xFF5CF2FF, false)).visibleWhen(() -> colorMode.get() == ColorMode.CUSTOM);
	private final NumberSetting fillAlpha = add(new NumberSetting("Fill Opacity", "Opacity of the box fill", 50, 0, 255, 1)).visibleWhen(() -> style.get() != Style.OUTLINE);
	private final NumberSetting lineWidth = add(new NumberSetting("Line Width", "Outline thickness", 2.0, 0.5, 6.0, 0.5)).visibleWhen(() -> style.get() != Style.FILL);
	private final NumberSetting range = add(new NumberSetting("Range", "Max distance to draw", 160, 16, 512, 8, "m"));
	private final NumberSetting maxBlocks = add(new NumberSetting("Max Blocks", "Max boxes drawn per frame (nearest first)", 1200, 50, 6000, 50));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to the nearest blocks", false));
	private final NumberSetting maxTracers = add(new NumberSetting("Max Tracers", "How many tracers to draw", 12, 1, 100, 1)).visibleWhen(tracers::get);
	private final BoolSetting labels = add(new BoolSetting("Labels", "Name + distance above the nearest blocks", false));
	private final BoolSetting throughWalls = add(new BoolSetting("Through Walls", "Draw on top of terrain", true));

	private final Long2ObjectOpenHashMap<List<BlockPos>> found = new Long2ObjectOpenHashMap<>();
	private Set<Block> targetCache = Set.of();
	private final it.unimi.dsi.fastutil.longs.LongOpenHashSet dirty = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private int tick;

	private List<Entry> sortedCache;

	private void refreshCache() {
		Vec3 cam = WorldUtil.cameraPos();
		double maxSq = (range.get() + 8) * (range.get() + 8);
		List<Entry> visible = new ArrayList<>();
		for (List<BlockPos> list : found.values())
			for (BlockPos p : list) {
				double d = p.distToCenterSqr(cam);
				if (d <= maxSq) visible.add(new Entry(p, d));
			}
		visible.sort((a, b) -> Double.compare(a.dist, b.dist));
		if (visible.size() > maxBlocks.getInt()) visible = new ArrayList<>(visible.subList(0, maxBlocks.getInt()));
		sortedCache = visible;
	}

	@Override
	public void onTick() {
		if (tick % 5 == 0) refreshCache();
		if (++tick % 5 == 0 && !dirty.isEmpty()) {
			for (long key : dirty.toLongArray()) {
				LevelChunk c = mc.level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
				if (c != null) scanChunk(c);
			}
			dirty.clear();
		}
	}
	private int total;

	public BlockESP() {
		super("BlockESP", "Highlights ores and chosen blocks through walls", Category.RENDER);
	}

	@Override
	public String getInfo() { return String.valueOf(total); }

	@Override
	protected void onEnable() {
		targetCache = new HashSet<>(blocks.get());
		super.onEnable();
	}

	@Override
	public void rescan() {
		targetCache = new HashSet<>(blocks.get());
		super.rescan();
	}

	@Override
	public void scanChunk(LevelChunk chunk) {
		Set<Block> targets = targetCache;
		if (targets.isEmpty()) return;
		boolean exposed = exposedOnly.get();
		List<BlockPos> list = new ArrayList<>();
		LevelChunkSection[] sections = chunk.getSections();
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section == null || section.hasOnlyAir()) continue;
			if (!section.getStates().maybeHas(s -> targets.contains(s.getBlock()))) continue;
			int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++)
						if (targets.contains(section.getBlockState(x, y, z).getBlock())) {
							BlockPos p = new BlockPos(baseX + x, baseY + y, baseZ + z);
							if (!exposed || XRay.isExposed(p, mc.level)) list.add(p);
						}
		}
		long key = chunk.getPos().toLong();
		List<BlockPos> old = found.remove(key);
		if (old != null) total -= old.size();
		if (!list.isEmpty()) {
			found.put(key, list);
			total += list.size();
		}
	}

	@Override
	public void onBlockChanged(LevelChunk chunk, BlockPos pos, BlockState oldState, BlockState newState) {
		if (exposedOnly.get()) {
			// A neighbour changing can expose / hide an ore: rescan the chunk(s) shortly.
			for (int dx = -1; dx <= 1; dx++)
				for (int dz = -1; dz <= 1; dz++)
					dirty.add(ChunkPos.asLong((pos.getX() + dx) >> 4, (pos.getZ() + dz) >> 4));
			return;
		}
		boolean was = targetCache.contains(oldState.getBlock()), is = targetCache.contains(newState.getBlock());
		if (was == is) return;
		long key = chunk.getPos().toLong();
		List<BlockPos> list = found.get(key);
		if (is) {
			if (list == null) found.put(key, list = new ArrayList<>());
			list.add(pos.immutable());
			total++;
		} else if (list != null && list.remove(pos)) {
			total--;
			if (list.isEmpty()) found.remove(key);
		}
	}

	@Override
	public void onChunkUnload(ChunkPos pos) {
		List<BlockPos> old = found.remove(pos.toLong());
		if (old != null) total -= old.size();
	}

	@Override
	public void clearAll() {
		sortedCache = null;
		dirty.clear();
		found.clear();
		total = 0;
	}

	private record Entry(BlockPos pos, double dist) {}

	@Override
	public void onRender3D(float partialTick) {
		if (found.isEmpty() || mc.level == null) return;
		Vec3 cam = WorldUtil.cameraPos();
		// The nearest-first list is rebuilt 4x per second (onTick), not every frame.
		if (sortedCache == null) refreshCache();
		List<Entry> visible = sortedCache;
		if (visible.isEmpty()) return;
		int limit = Math.min(visible.size(), maxBlocks.getInt());
		boolean walls = throughWalls.get();
		for (int i = 0; i < limit; i++) {
			Entry e = visible.get(i);
			BlockState state = mc.level.getBlockState(e.pos);
			if (!targetCache.contains(state.getBlock())) continue; // mined / changed this frame
			int base = colorMode.get() == ColorMode.CUSTOM ? color.get() : BlockColors.get(state.getBlock());
			// Fade boxes out smoothly near the edge of the range.
			float edge = (float) Math.min(1, (range.get() - Math.sqrt(e.pos.distToCenterSqr(cam))) / 16.0);
			if (edge <= 0) continue;
			int fill = style.get() == Style.OUTLINE ? 0 : ColorUtil.withAlpha(base, (int) (fillAlpha.get() * edge));
			int line = style.get() == Style.FILL ? 0 : ColorUtil.withAlpha(base, (int) (230 * edge));
			AABB box = new AABB(e.pos).deflate(0.002);
			Render3D.box(box, fill, line, lineWidth.getFloat(), walls);
			if (Render3D.look(box)) Render3D.lookText(state.getBlock().getName().getString(), base);
		}
		if (tracers.get()) {
			int n = Math.min(limit, maxTracers.getInt());
			for (int i = 0; i < n; i++) {
				Entry e = visible.get(i);
				int base = colorMode.get() == ColorMode.CUSTOM ? color.get() : BlockColors.get(mc.level.getBlockState(e.pos).getBlock());
				Render3D.tracer(Vec3.atCenterOf(e.pos), ColorUtil.withAlpha(base, 200), 1.5f);
			}
		}
		if (labels.get()) {
			int n = Math.min(limit, 24);
			for (int i = 0; i < n; i++) {
				Entry e = visible.get(i);
				Block block = mc.level.getBlockState(e.pos).getBlock();
				int base = colorMode.get() == ColorMode.CUSTOM ? color.get() : BlockColors.get(block);
				Render3D.label(Vec3.atCenterOf(e.pos).add(0, 0.85, 0), block.getName().getString() + " §7" + (int) Math.sqrt(e.dist) + "m", base, 1f);
			}
		}
	}

	public List<BlockPos> snapshot() {
		List<BlockPos> all = new ArrayList<>();
		for (List<BlockPos> l : found.values()) all.addAll(l);
		return Collections.unmodifiableList(all);
	}
}
