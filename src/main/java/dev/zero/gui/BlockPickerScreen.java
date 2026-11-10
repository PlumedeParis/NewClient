package dev.zero.gui;

import dev.zero.config.ConfigManager;
import dev.zero.gui.comp.TextField;
import dev.zero.render.Render2D;
import dev.zero.setting.BlockListSetting;
import dev.zero.util.Anim;
import dev.zero.util.BlockColors;
import dev.zero.util.ColorUtil;
import dev.zero.util.Sounds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Searchable grid of every block with item icons, used by BlockESP's block list. */
public class BlockPickerScreen extends Screen {
	private record Entry(Block block, ItemStack stack, String name, String id) {}

	private static final int CELL = 22;
	private final BlockListSetting setting;
	private final Screen parent;
	private final List<Entry> all = new ArrayList<>();
	private List<Entry> filtered = new ArrayList<>();
	private final TextField search;
	private final Anim scroll = new Anim(0, 16);
	private double scrollTarget;
	private final Anim open = new Anim(0, 12);
	private boolean onlySelected;

	public BlockPickerScreen(BlockListSetting setting, Screen parent) {
		super(Component.literal("Block Picker"));
		this.setting = setting;
		this.parent = parent;
		for (Block b : BuiltInRegistries.BLOCK) {
			if (b.defaultBlockState().isAir()) continue;
			String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
			all.add(new Entry(b, new ItemStack(b.asItem()), b.getName().getString(), id));
		}
		search = new TextField("Search blocks...", 40, null, t -> refilter());
		TextField.focused = search;
		open.setTarget(1);
		refilter();
	}

	private void refilter() {
		String q = search == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
		List<Entry> sel = new ArrayList<>(), rest = new ArrayList<>();
		for (Entry e : all) {
			if (!q.isEmpty() && !e.name.toLowerCase(Locale.ROOT).contains(q) && !e.id.contains(q)) continue;
			if (setting.contains(e.block)) sel.add(e);
			else if (!onlySelected) rest.add(e);
		}
		filtered = new ArrayList<>(sel);
		filtered.addAll(rest);
	}

	private int[] window() {
		int w = Math.min(width - 40, 420), h = height - 50;
		w -= (w - 16) % CELL;
		return new int[] {width / 2 - w / 2, 25, w, h};
	}

	private static final String[] PRESETS = {"Ores", "Valuables", "Storage", "Selected", "Clear", "Done"};

	private int[] presetRect(int i) {
		int[] win = window();
		int bw = (win[2] - 16 - 5 * 4) / 6;
		return new int[] {win[0] + 8 + i * (bw + 4), win[1] + 42, bw, 14};
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fillGradient(0, 0, width, height, 0xA005050A, 0xC00B0B18);
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float partialTick) {
		super.render(g, mx, my, partialTick);
		float o = (float) Anim.easeOutCubic(open.get());
		int[] win = window();
		int x = win[0], y = (int) (win[1] + (1 - o) * 16), w = win[2], h = win[3];
		int accent = Theme.accent();

		Render2D.glow(g, x, y, w, h, 8, ColorUtil.withAlpha(accent, 60), 8);
		Render2D.roundedRect(g, x, y, w, h, 8, Theme.panelBg());
		Render2D.gradientH(g, x + 8, y, w - 16, 1, accent, Theme.accent2());
		Render2D.text(g, setting.getName(), x + 9, y + 8, Theme.TEXT, false);
		String count = setting.get().size() + " selected";
		Render2D.text(g, count, x + w - 9 - Render2D.width(count), y + 8, accent, false);
		search.render(g, x + 8, y + 22, w - 16, 15, Theme.FIELD);

		for (int i = 0; i < PRESETS.length; i++) {
			int[] r = presetRect(i);
			r[1] = r[1] + (y - win[1]);
			boolean hover = Render2D.hovered(mx, my, r[0], r[1], r[2], r[3]);
			boolean active = i == 3 && onlySelected;
			int bg = i == 5 ? accent : active ? ColorUtil.withAlpha(accent, 140) : hover ? 0xFF2A2A38 : Theme.FIELD;
			Render2D.roundedRect(g, r[0], r[1], r[2], r[3], 4, bg);
			Render2D.centeredText(g, PRESETS[i], r[0] + r[2] / 2, r[1] + 3, i == 5 || active ? 0xFFFFFFFF : Theme.SUBTEXT, false);
		}

		int gx = x + 8, gy = y + 62, gw = w - 16, gh = h - 70;
		int cols = Math.max(1, gw / CELL);
		int rows = (filtered.size() + cols - 1) / cols;
		double maxScroll = Math.max(0, rows * CELL - gh);
		scrollTarget = Math.max(-maxScroll, Math.min(0, scrollTarget));
		int sc = (int) scroll.setTarget(scrollTarget).get();

		g.enableScissor(gx, gy, gx + gw, gy + gh);
		Entry hovered = null;
		for (int i = 0; i < filtered.size(); i++) {
			int cx = gx + (i % cols) * CELL, cy = gy + (i / cols) * CELL + sc;
			if (cy + CELL < gy || cy > gy + gh) continue;
			Entry e = filtered.get(i);
			boolean sel = setting.contains(e.block);
			boolean hover = Render2D.hovered(mx, my, cx, cy, CELL - 2, CELL - 2) && my >= gy && my < gy + gh;
			if (hover) hovered = e;
			int bg = sel ? ColorUtil.withAlpha(accent, 90) : hover ? 0x30FFFFFF : 0x14FFFFFF;
			Render2D.roundedRect(g, cx, cy, CELL - 2, CELL - 2, 4, bg);
			if (sel) Render2D.rect(g, cx + 3, cy + CELL - 4, CELL - 8, 1, accent);
			if (!e.stack.isEmpty()) g.renderItem(e.stack, cx + 2, cy + 2);
			else {
				int c = BlockColors.get(e.block);
				Render2D.roundedRect(g, cx + 4, cy + 4, CELL - 10, CELL - 10, 3, ColorUtil.withAlpha(c, 200));
				Render2D.centeredText(g, e.name.substring(0, 1), cx + CELL / 2 - 1, cy + 7, 0xFFFFFFFF, true);
			}
		}
		g.disableScissor();
		if (rows * CELL > gh) {
			int barH = Math.max(20, (int) (gh * (gh / (double) (rows * CELL))));
			int barY = gy + (int) ((gh - barH) * (-sc / Math.max(1, maxScroll)));
			Render2D.roundedRect(g, x + w - 5, barY, 3, barH, 1, ColorUtil.withAlpha(accent, 160));
		}
		if (hovered != null) {
			String tip = hovered.name + "  §8" + hovered.id;
			int tw = Render2D.width(tip) + 10;
			int tx = Math.min(mx + 10, width - tw - 4);
			Render2D.roundedRect(g, tx, my - 14, tw, 13, 4, 0xF0101016);
			Render2D.text(g, tip, tx + 5, my - 11, Theme.TEXT, false);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		int[] win = window();
		for (int i = 0; i < PRESETS.length; i++) {
			int[] r = presetRect(i);
			if (Render2D.hovered(mx, my, r[0], r[1], r[2], r[3])) {
				preset(i);
				Sounds.click();
				return true;
			}
		}
		if (Render2D.hovered(mx, my, win[0] + 8, win[1] + 22, win[2] - 16, 15)) {
			TextField.focused = search;
			if (event.button() == 1) search.setText("");
			return true;
		}
		int gx = win[0] + 8, gy = win[1] + 62, gw = win[2] - 16, gh = win[3] - 70;
		if (mx >= gx && mx < gx + gw && my >= gy && my < gy + gh) {
			int cols = Math.max(1, gw / CELL);
			int col = (int) ((mx - gx) / CELL), row = (int) ((my - gy - scroll.get()) / CELL);
			int idx = row * cols + col;
			if (col < cols && idx >= 0 && idx < filtered.size()) {
				setting.toggle(filtered.get(idx).block);
				ConfigManager.markDirty();
				Sounds.click();
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void preset(int i) {
		switch (i) {
			case 0 -> {
				Set<Block> s = new LinkedHashSet<>(setting.get());
				for (Entry e : all) if (e.id.endsWith("_ore")) s.add(e.block);
				s.add(Blocks.ANCIENT_DEBRIS);
				setting.set(s);
			}
			case 1 -> {
				Set<Block> s = new LinkedHashSet<>(setting.get());
				s.addAll(List.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.ANCIENT_DEBRIS, Blocks.EMERALD_ORE,
					Blocks.DEEPSLATE_EMERALD_ORE, Blocks.SPAWNER, Blocks.BUDDING_AMETHYST));
				setting.set(s);
			}
			case 2 -> {
				Set<Block> s = new LinkedHashSet<>(setting.get());
				for (Entry e : all) if (e.id.endsWith("shulker_box")) s.add(e.block);
				s.addAll(List.of(Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST, Blocks.HOPPER));
				setting.set(s);
			}
			case 3 -> onlySelected = !onlySelected;
			case 4 -> setting.set(new LinkedHashSet<>());
			case 5 -> {
				onClose();
				return;
			}
			default -> {}
		}
		ConfigManager.markDirty();
		refilter();
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		scrollTarget += vertical * CELL * 2;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			onClose();
			return true;
		}
		if (search.keyPressed(event.key())) return true;
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		String s = event.codepointAsString();
		TextField.focused = search;
		return !s.isEmpty() && search.charTyped(s.charAt(0));
	}

	@Override
	public void onClose() {
		TextField.focused = null;
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() { return false; }
}
