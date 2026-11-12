package dev.zero.util;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.IdentityHashMap;
import java.util.Map;

/** Hand-picked vivid colors for ores and notable blocks; falls back to the block's map color. */
public final class BlockColors {
	private static final Map<Block, Integer> COLORS = new IdentityHashMap<>();

	static {
		put(0xFF5CF2FF, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.DIAMOND_BLOCK);
		put(0xFFFF8A4C, Blocks.ANCIENT_DEBRIS, Blocks.NETHERITE_BLOCK);
		put(0xFF3CF07A, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE, Blocks.EMERALD_BLOCK);
		put(0xFFFFD54A, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE, Blocks.GOLD_BLOCK, Blocks.RAW_GOLD_BLOCK, Blocks.GILDED_BLACKSTONE);
		put(0xFFE8B48F, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.RAW_IRON_BLOCK, Blocks.IRON_BLOCK);
		put(0xFFFF4040, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.REDSTONE_BLOCK);
		put(0xFF3E6CFF, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE, Blocks.LAPIS_BLOCK);
		put(0xFF6B6B78, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.COAL_BLOCK);
		put(0xFFE07A4A, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.RAW_COPPER_BLOCK);
		put(0xFFF2EBDD, Blocks.NETHER_QUARTZ_ORE);
		put(0xFFFF55FF, Blocks.SPAWNER, Blocks.TRIAL_SPAWNER);
		put(0xFFFFB347, Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL);
		put(0xFFB98CFF, Blocks.ENDER_CHEST, Blocks.SHULKER_BOX);
		put(0xFFC9A0FF, Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_CLUSTER);
		put(0xFF8AE3FF, Blocks.BEACON);
		put(0xFF9C6BFF, Blocks.NETHER_PORTAL, Blocks.END_PORTAL_FRAME, Blocks.END_PORTAL);
		put(0xFF2B2140, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN);
	}

	private BlockColors() {}

	private static void put(int color, Block... blocks) {
		for (Block b : blocks) COLORS.put(b, color);
	}

	/** User-chosen colors (Block Colors editor), saved in the profile. */
	private static final Map<Block, Integer> CUSTOM = new IdentityHashMap<>();

	public static boolean isCustom(Block block) { return CUSTOM.containsKey(block); }

	public static void setCustom(Block block, int argb) { CUSTOM.put(block, argb | 0xFF000000); }

	public static void resetCustom(Block block) { CUSTOM.remove(block); }

	public static void clearCustom() { CUSTOM.clear(); }

	/** The built-in color, ignoring user overrides. */
	public static int defaultColor(Block block) {
		Integer c = COLORS.get(block);
		if (c != null) return c;
		int map = block.defaultMapColor().col;
		return map == 0 ? 0xFFFFFFFF : 0xFF000000 | map;
	}

	public static com.google.gson.JsonObject save() {
		com.google.gson.JsonObject o = new com.google.gson.JsonObject();
		for (Map.Entry<Block, Integer> e : CUSTOM.entrySet())
			o.addProperty(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(e.getKey()).toString(), String.format("#%08X", e.getValue()));
		return o;
	}

	public static void load(com.google.gson.JsonObject o) {
		CUSTOM.clear();
		for (String key : o.keySet()) {
			var id = net.minecraft.resources.Identifier.tryParse(key);
			if (id == null) continue;
			try {
				int c = (int) Long.parseLong(o.get(key).getAsString().replace("#", ""), 16);
				net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(id).ifPresent(b -> CUSTOM.put(b, c));
			} catch (Exception ignored) {
			}
		}
	}

	public static int get(Block block) {
		Integer custom = CUSTOM.get(block);
		if (custom != null) return custom;
		Integer c = COLORS.get(block);
		if (c != null) return c;
		int map = block.defaultMapColor().col;
		return map == 0 ? 0xFFFFFFFF : 0xFF000000 | map;
	}
}
