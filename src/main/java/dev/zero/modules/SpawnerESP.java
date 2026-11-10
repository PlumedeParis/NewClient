package dev.zero.modules;

import dev.zero.module.Category;
import dev.zero.module.ScanningModule;
import dev.zero.render.Render3D;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.ColorSetting;
import dev.zero.setting.EnumSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.util.ColorUtil;
import dev.zero.util.WorldUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Highlights every mob spawner and trial spawner through walls, colored by mob type. */
public class SpawnerESP extends ScanningModule {
	public enum ColorMode { BY_MOB, SINGLE }

	private final BoolSetting spawners = add(new BoolSetting("Spawners", "Normal mob spawners", true)).onChange(this::rescan);
	private final BoolSetting trial = add(new BoolSetting("Trial Spawners", "Trial chamber spawners", true)).onChange(this::rescan);
	private final EnumSetting<ColorMode> colorMode = add(new EnumSetting<>("Color Mode", "Color by mob type or one color", ColorMode.BY_MOB));
	private final ColorSetting color = add(new ColorSetting("Color", "Spawner color", 0xFFFF55FF, false)).visibleWhen(() -> colorMode.get() == ColorMode.SINGLE);
	private final NumberSetting fillAlpha = add(new NumberSetting("Fill Opacity", "Box fill opacity", 50, 0, 255, 1));
	private final NumberSetting lineWidth = add(new NumberSetting("Line Width", "Outline thickness", 2, 0.5, 6, 0.5));
	private final NumberSetting range = add(new NumberSetting("Range", "Max distance", 256, 16, 512, 8, "m"));
	private final BoolSetting labels = add(new BoolSetting("Labels", "Mob type + distance", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to spawners", true));
	private final BoolSetting activation = add(new BoolSetting("Activation Range", "Show the 16-block range where a spawner is active", false));

	private record Entry(BlockPos pos, String mob, boolean trial) {}

	private static final Map<String, Integer> MOB_COLORS = Map.ofEntries(
		Map.entry("zombie", 0xFF4CAF50), Map.entry("husk", 0xFFC2A15A), Map.entry("drowned", 0xFF2EC4B6), Map.entry("skeleton", 0xFFE0E0E0),
		Map.entry("stray", 0xFFA7C7E7), Map.entry("bogged", 0xFF7C9A3D), Map.entry("spider", 0xFF8B1E1E), Map.entry("cave_spider", 0xFF1E7F8B),
		Map.entry("blaze", 0xFFFF9800), Map.entry("silverfish", 0xFF9E9E9E), Map.entry("magma_cube", 0xFFFF5722), Map.entry("creeper", 0xFF76FF03),
		Map.entry("pig", 0xFFFF9EB5), Map.entry("cow", 0xFF8D6E63), Map.entry("chicken", 0xFFFFF59D), Map.entry("sheep", 0xFFF5F5F5),
		Map.entry("iron_golem", 0xFFCFD8DC), Map.entry("slime", 0xFF8BC34A), Map.entry("enderman", 0xFFB388FF), Map.entry("breeze", 0xFF80DEEA));

	private final Long2ObjectOpenHashMap<List<Entry>> found = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet dirty = new LongOpenHashSet();
	private int total, tick;

	public SpawnerESP() {
		super("SpawnerESP", "Highlights mob spawners through walls, colored by mob", Category.RENDER);
	}

	@Override
	public String getInfo() { return String.valueOf(total); }

	@Override
	public void scanChunk(LevelChunk chunk) {
		List<Entry> list = new ArrayList<>();
		for (BlockEntity be : chunk.getBlockEntities().values()) {
			if (be instanceof SpawnerBlockEntity sp && spawners.get()) {
				String mob = "spawner";
				try {
					Entity e = sp.getSpawner().getOrCreateDisplayEntity(mc.level, be.getBlockPos());
					if (e != null) mob = EntityType.getKey(e.getType()).getPath();
				} catch (Exception ignored) {
				}
				list.add(new Entry(be.getBlockPos().immutable(), mob, false));
			} else if (be instanceof TrialSpawnerBlockEntity && trial.get()) {
				list.add(new Entry(be.getBlockPos().immutable(), "trial spawner", true));
			}
		}
		long key = chunk.getPos().toLong();
		List<Entry> old = found.remove(key);
		if (old != null) total -= old.size();
		if (!list.isEmpty()) {
			found.put(key, list);
			total += list.size();
		}
	}

	@Override
	public void onBlockChanged(LevelChunk chunk, BlockPos pos, BlockState o, BlockState n) {
		if (o.is(Blocks.SPAWNER) || n.is(Blocks.SPAWNER) || o.is(Blocks.TRIAL_SPAWNER) || n.is(Blocks.TRIAL_SPAWNER)) dirty.add(chunk.getPos().toLong());
	}

	@Override
	public void onTick() {
		// Spawn data arrives with the block entity update, slightly after the block itself.
		if (++tick % 20 == 0) {
			if (!dirty.isEmpty()) {
				for (long k : dirty.toLongArray()) {
					LevelChunk c = mc.level.getChunkSource().getChunk(ChunkPos.getX(k), ChunkPos.getZ(k), ChunkStatus.FULL, false);
					if (c != null) scanChunk(c);
				}
				dirty.clear();
			}
			for (var e : found.long2ObjectEntrySet()) for (Entry s : e.getValue()) if (s.mob.equals("spawner")) dirty.add(e.getLongKey());
		}
	}

	@Override
	public void onChunkUnload(ChunkPos pos) {
		List<Entry> old = found.remove(pos.toLong());
		if (old != null) total -= old.size();
	}

	@Override
	public void clearAll() {
		found.clear();
		dirty.clear();
		total = 0;
	}

	private int colorOf(Entry e) {
		if (colorMode.get() == ColorMode.SINGLE) return color.get();
		if (e.trial) return 0xFFFF8A3D;
		Integer c = MOB_COLORS.get(e.mob);
		if (c != null) return c;
		return ColorUtil.fromHsv((e.mob.hashCode() & 0xFFFF) / 65535f, 0.65f, 1f, 255);
	}

	@Override
	public void onRender3D(float pt) {
		Vec3 cam = WorldUtil.cameraPos();
		double max = range.get();
		for (List<Entry> list : found.values()) {
			for (Entry e : list) {
				Vec3 center = Vec3.atCenterOf(e.pos);
				double d = cam.distanceTo(center);
				if (d > max) continue;
				int c = colorOf(e);
				float edge = (float) Mth.clamp((max - d) / 16.0, 0, 1);
				Render3D.box(new AABB(e.pos), ColorUtil.withAlpha(c, (int) (fillAlpha.get() * edge)), ColorUtil.withAlpha(c, (int) (240 * edge)), lineWidth.getFloat(), true);
				if (tracers.get()) Render3D.tracer(center, ColorUtil.withAlpha(c, 200), 1.6f);
				if (labels.get()) {
					String name = e.mob.replace('_', ' ');
					name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
					Render3D.label(center.add(0, 1, 0), name + " §7" + WorldUtil.distance(d), c, 1f);
				}
				if (activation.get()) {
					boolean inRange = mc.player != null && mc.player.position().distanceTo(center) <= 16;
					Render3D.box(new AABB(e.pos).inflate(16), 0, ColorUtil.withAlpha(inRange ? 0xFF4ADE80 : c, 90), 1f, false);
				}
			}
		}
	}

	public String describe() { return String.format(Locale.ROOT, "%d spawners", total); }
}
