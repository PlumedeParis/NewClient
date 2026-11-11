package dev.zero.modules;

import dev.zero.module.Category;
import dev.zero.module.HuntTarget;
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
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Highlights storage blocks and storage entities through walls, with per-type colors,
 * merged double chests, dye-colored shulkers and per-chunk storage counts.
 * Reads block entities from chunk data the server already sent.
 */
public class StorageESP extends ScanningModule implements HuntTarget.Provider {
	public enum Style { BOTH, OUTLINE, FILL }

	private enum Type { CHEST, TRAPPED, ENDER, SHULKER, BARREL, HOPPER, FURNACE, DISPENSER, BREWING }

	private final BoolSetting chests = add(new BoolSetting("Chests", "Chests and trapped chests", true)).onChange(this::rescan);
	private final BoolSetting shulkers = add(new BoolSetting("Shulkers", "Shulker boxes (in their own color)", true)).onChange(this::rescan);
	private final BoolSetting enderChests = add(new BoolSetting("Ender Chests", "Ender chests", true)).onChange(this::rescan);
	private final BoolSetting barrels = add(new BoolSetting("Barrels", "Barrels", true)).onChange(this::rescan);
	private final BoolSetting hoppers = add(new BoolSetting("Hoppers", "Hoppers", true)).onChange(this::rescan);
	private final BoolSetting furnaces = add(new BoolSetting("Furnaces", "Furnaces, smokers, blast furnaces", false)).onChange(this::rescan);
	private final BoolSetting dispensers = add(new BoolSetting("Dispensers", "Dispensers and droppers", false)).onChange(this::rescan);
	private final BoolSetting brewing = add(new BoolSetting("Brewing Stands", "Brewing stands", false)).onChange(this::rescan);
	private final BoolSetting entities = add(new BoolSetting("Entities", "Chest minecarts, chest boats, hopper minecarts", true));

	private final ColorSetting chestColor = add(new ColorSetting("Chest Color", "Chest color", 0xFFFFB347, false));
	private final ColorSetting trappedColor = add(new ColorSetting("Trapped Color", "Trapped chest color", 0xFFFF5C5C, false));
	private final ColorSetting enderColor = add(new ColorSetting("Ender Color", "Ender chest color", 0xFFB46BFF, false));
	private final BoolSetting dyeShulkers = add(new BoolSetting("Dye Shulkers", "Use each shulker's dye color", true));
	private final ColorSetting shulkerColor = add(new ColorSetting("Shulker Color", "Undyed shulker color", 0xFFE879F9, false));
	private final ColorSetting barrelColor = add(new ColorSetting("Barrel Color", "Barrel color", 0xFFC08A5B, false));
	private final ColorSetting otherColor = add(new ColorSetting("Other Color", "Hoppers, furnaces, dispensers...", 0xFF9CA3AF, false));

	private final EnumSetting<Style> style = add(new EnumSetting<>("Style", "How storage is drawn", Style.BOTH));
	private final NumberSetting fillAlpha = add(new NumberSetting("Fill Opacity", "Opacity of the box fill", 45, 0, 255, 1)).visibleWhen(() -> style.get() != Style.OUTLINE);
	private final NumberSetting lineWidth = add(new NumberSetting("Line Width", "Outline thickness", 2.0, 0.5, 6.0, 0.5)).visibleWhen(() -> style.get() != Style.FILL);
	private final NumberSetting range = add(new NumberSetting("Range", "Max distance to draw", 192, 16, 512, 8, "m"));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to storage", false));
	private final BoolSetting chunkCounts = add(new BoolSetting("Stash Counts", "Label chunks with many storage blocks", true));
	private final NumberSetting countThreshold = add(new NumberSetting("Count Threshold", "Storage blocks in a chunk to show a count", 6, 2, 64, 1)).visibleWhen(chunkCounts::get);

	private record Entry(BlockPos pos, Type type, AABB box, int dye) {}

	private final Long2ObjectOpenHashMap<List<Entry>> found = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet dirty = new LongOpenHashSet();
	private int total, tick;

	public StorageESP() {
		super("StorageESP", "Highlights chests, shulkers and other storage", Category.RENDER);
	}

	@Override
	public String getInfo() { return String.valueOf(total); }

	private Type typeOf(BlockEntity be) {
		if (be instanceof TrappedChestBlockEntity) return chests.get() ? Type.TRAPPED : null;
		if (be instanceof ChestBlockEntity) return chests.get() ? Type.CHEST : null;
		if (be instanceof EnderChestBlockEntity) return enderChests.get() ? Type.ENDER : null;
		if (be instanceof ShulkerBoxBlockEntity) return shulkers.get() ? Type.SHULKER : null;
		if (be instanceof BarrelBlockEntity) return barrels.get() ? Type.BARREL : null;
		if (be instanceof HopperBlockEntity) return hoppers.get() ? Type.HOPPER : null;
		if (be instanceof AbstractFurnaceBlockEntity) return furnaces.get() ? Type.FURNACE : null;
		if (be instanceof DispenserBlockEntity) return dispensers.get() ? Type.DISPENSER : null;
		if (be instanceof BrewingStandBlockEntity) return brewing.get() ? Type.BREWING : null;
		return null;
	}

	@Override
	public void scanChunk(LevelChunk chunk) {
		List<Entry> list = new ArrayList<>();
		for (BlockEntity be : chunk.getBlockEntities().values()) {
			Type type = typeOf(be);
			if (type == null) continue;
			BlockPos pos = be.getBlockPos();
			BlockState state = be.getBlockState();
			AABB box;
			if (type == Type.CHEST || type == Type.TRAPPED) {
				AABB single = new AABB(pos.getX() + 0.0625, pos.getY(), pos.getZ() + 0.0625, pos.getX() + 0.9375, pos.getY() + 0.875, pos.getZ() + 0.9375);
				if (state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
					if (state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) continue; // drawn by the left half
					Direction d = ChestBlock.getConnectedDirection(state);
					box = single.minmax(single.move(d.getStepX(), 0, d.getStepZ()));
				} else box = single;
			} else if (type == Type.ENDER) {
				box = new AABB(pos.getX() + 0.0625, pos.getY(), pos.getZ() + 0.0625, pos.getX() + 0.9375, pos.getY() + 0.875, pos.getZ() + 0.9375);
			} else box = new AABB(pos);
			int dye = 0;
			if (type == Type.SHULKER && be instanceof ShulkerBoxBlockEntity s) {
				DyeColor c = s.getColor();
				if (c != null) dye = 0xFF000000 | c.getTextureDiffuseColor();
			}
			list.add(new Entry(pos.immutable(), type, box, dye));
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
	public void onBlockChanged(LevelChunk chunk, BlockPos pos, BlockState oldState, BlockState newState) {
		if (oldState.hasBlockEntity() || newState.hasBlockEntity()) dirty.add(chunk.getPos().toLong());
	}

	@Override
	public void onTick() {
		if (++tick % 5 == 0 && !dirty.isEmpty()) {
			for (long key : dirty.toLongArray()) {
				LevelChunk c = mc.level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false);
				if (c != null) scanChunk(c);
			}
			dirty.clear();
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

	private int color(Entry e) {
		return switch (e.type) {
			case CHEST -> chestColor.get();
			case TRAPPED -> trappedColor.get();
			case ENDER -> enderColor.get();
			case SHULKER -> dyeShulkers.get() && e.dye != 0 ? e.dye : shulkerColor.get();
			case BARREL -> barrelColor.get();
			default -> otherColor.get();
		};
	}

	private void draw(AABB box, int base, double dist) {
		float edge = (float) Mth.clamp((range.get() - dist) / 16.0, 0, 1);
		int fill = style.get() == Style.OUTLINE ? 0 : ColorUtil.withAlpha(base, (int) (fillAlpha.get() * edge));
		int line = style.get() == Style.FILL ? 0 : ColorUtil.withAlpha(base, (int) (235 * edge));
		Render3D.box(box, fill, line, lineWidth.getFloat(), true);
	}

	@Override
	public void onRender3D(float pt) {
		Vec3 cam = WorldUtil.cameraPos();
		double max = range.get();
		for (var e : found.long2ObjectEntrySet()) {
			List<Entry> list = e.getValue();
			int shown = 0;
			for (Entry s : list) {
				double d = s.box.getCenter().distanceTo(cam);
				if (d > max) continue;
				if (!mc.level.getBlockState(s.pos).hasBlockEntity()) continue; // broken this frame
				shown++;
				int c = color(s);
				draw(s.box, c, d);
				if (tracers.get()) Render3D.tracer(s.box.getCenter(), ColorUtil.withAlpha(c, 180), 1.4f);
			}
			if (chunkCounts.get() && shown > 0 && list.size() >= countThreshold.getInt()) {
				ChunkPos cp = new ChunkPos(e.getLongKey());
				double y = list.stream().mapToDouble(s -> s.box.maxY).max().orElse(64) + 1.2;
				Render3D.label(new Vec3(cp.getMiddleBlockX() + 0.5, y, cp.getMiddleBlockZ() + 0.5), list.size() + " storage", chestColor.get(), 1.15f);
			}
		}
		if (entities.get()) {
			for (Entity en : mc.level.entitiesForRendering()) {
				if (!(en instanceof ContainerEntity)) continue;
				double d = en.position().distanceTo(cam);
				if (d > max) continue;
				AABB bb = en.getBoundingBox().move(
					Mth.lerp(pt, en.xo, en.getX()) - en.getX(), Mth.lerp(pt, en.yo, en.getY()) - en.getY(), Mth.lerp(pt, en.zo, en.getZ()) - en.getZ());
				draw(bb, chestColor.get(), d);
			}
		}
	}

	@Override
	public void collectTargets(List<HuntTarget> out) {
		for (List<Entry> list : found.values()) {
			if (list.size() < countThreshold.getInt()) continue;
			out.add(new HuntTarget(BlockPos.containing(list.get(0).box.getCenter()), Math.min(90, 30 + list.size() * 4), list.size() + " storage blocks", 0xFFFFB347));
		}
	}

}
