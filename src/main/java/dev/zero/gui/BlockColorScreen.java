package dev.zero.gui;

import dev.zero.config.ConfigManager;
import dev.zero.gui.comp.TextField;
import dev.zero.render.Render2D;
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
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Choose the color ZERØ uses for any block (ESP boxes, ore holograms, Look Info...).
 * Ores are listed first; changing an ore can also change its deepslate variant.
 */
public class BlockColorScreen extends Screen {
	private record Entry(Block block, ItemStack stack, String name, String id, int priority) {}

	private static final int CELL = 22;
	private static final int[] SWATCHES = {0xFFFFFFFF, 0xFFFF3B3B, 0xFFFF8A00, 0xFFFFD500, 0xFF7CFF4D, 0xFF00E676, 0xFF00E5FF, 0xFF2979FF,
		0xFF7C4DFF, 0xFFFF4DD8, 0xFF8D6E63, 0xFF000000};

	private final Screen parent;
	private final List<Entry> all = new ArrayList<>();
	private List<Entry> filtered = new ArrayList<>();
	private final TextField search = new TextField("Search blocks...", 40, null, t -> refilter());
	private final TextField hex = new TextField("#RRGGBB", 7, t -> applyHex(), null);
	private final Anim scroll = new Anim(0, 16);
	private double scrollTarget;
	private Block selected = Blocks.COAL_ORE;
	private float hue, sat, val;
	private int drag; // 1 = SV square, 2 = hue bar
	private static boolean linkVariants = true;

	public BlockColorScreen(Screen parent) {
		super(Component.literal("Block Colors"));
		this.parent = parent;
		for (Block b : BuiltInRegistries.BLOCK) {
			if (b.defaultBlockState().isAir()) continue;
			String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
			int pri = id.endsWith("_ore") || b == Blocks.ANCIENT_DEBRIS ? 0 : id.contains("chest") || id.contains("shulker") || b == Blocks.SPAWNER ? 1 : 2;
			all.add(new Entry(b, new ItemStack(b.asItem()), b.getName().getString(), id, pri));
		}
		all.sort((a, b) -> a.priority != b.priority ? Integer.compare(a.priority, b.priority) : a.name.compareToIgnoreCase(b.name));
		select(selected);
		refilter();
	}

	private void refilter() {
		String q = search.getText().toLowerCase(Locale.ROOT).trim();
		List<Entry> out = new ArrayList<>();
		for (Entry e : all) if (q.isEmpty() || e.name.toLowerCase(Locale.ROOT).contains(q) || e.id.contains(q)) out.add(e);
		filtered = out;
	}

	private void select(Block b) {
		selected = b;
		float[] hsv = ColorUtil.toHsv(BlockColors.get(b));
		hue = hsv[0];
		sat = hsv[1];
		val = hsv[2];
		hex.setText(String.format("#%06X", BlockColors.get(b) & 0xFFFFFF));
	}

	/** The ore / deepslate-ore twin of a block, or null. */
	private static Block variant(Block b) {
		String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
		String other = id.startsWith("deepslate_") ? id.substring("deepslate_".length()) : "deepslate_" + id;
		if (!other.endsWith("_ore") && !id.endsWith("_ore")) return null;
		return BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(other)).orElse(null);
	}

	/** Test hook: pick a block and give it a color through the same path as the editor. */
	public void testSetColor(Block b, int rgb) { select(b); setColor(rgb); select(b); }

	private void setColor(int rgb) {
		BlockColors.setCustom(selected, rgb);
		Block v = linkVariants ? variant(selected) : null;
		if (v != null) BlockColors.setCustom(v, rgb);
		hex.setText(String.format("#%06X", rgb & 0xFFFFFF));
		ConfigManager.markDirty();
	}

	private void applyHex() {
		try {
			int rgb = Integer.parseInt(hex.getText().replace("#", "").trim(), 16);
			setColor(0xFF000000 | rgb);
			select(selected);
		} catch (NumberFormatException ignored) {
		}
		TextField.focused = null;
	}

	// ---- Layout -------------------------------------------------------------------------------
	private int[] win() {
		int w = Math.min(width - 16, 470), h = Math.min(height - 16, 270);
		return new int[] {width / 2 - w / 2, height / 2 - h / 2, w, h};
	}

	private int panelW() { return 150; }

	private int[] grid() {
		int[] w = win();
		int gw = w[2] - panelW() - 24;
		gw -= gw % CELL;
		return new int[] {w[0] + 8, w[1] + 42, gw, w[3] - 50};
	}

	private int[] sv() {
		int[] w = win();
		int px = w[0] + w[2] - panelW() - 8;
		return new int[] {px, w[1] + 64, panelW() - 14, 56};
	}

	private int[] hueBar() {
		int[] s = sv();
		return new int[] {s[0] + s[2] + 4, s[1], 10, s[3]};
	}

	private int[] swatch(int i) {
		int[] s = sv();
		return new int[] {s[0] + (i % 6) * 24, s[1] + s[3] + 6 + (i / 6) * 14, 20, 11};
	}

	private int[] hexRect() { int[] s = sv(); return new int[] {s[0], s[1] + s[3] + 38, 70, 14}; }

	private int[] resetRect() { int[] s = sv(); return new int[] {s[0] + 74, s[1] + s[3] + 38, s[2] + 14 - 74, 14}; }

	private int[] linkRect() { int[] s = sv(); return new int[] {s[0], s[1] + s[3] + 56, s[2] + 14, 14}; }

	private int[] doneRect() { int[] w = win(); return new int[] {w[0] + w[2] - 8 - 60, w[1] + w[3] - 22, 60, 14}; }

	// ---- Rendering ----------------------------------------------------------------------------
	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fillGradient(0, 0, width, height, 0xA005050A, 0xC00B0B18);
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float pt) {
		super.render(g, mx, my, pt);
		if (drag != 0) updateDrag(mx, my);
		int[] w = win();
		int accent = Theme.accent();
		Render2D.glow(g, w[0], w[1], w[2], w[3], 8, ColorUtil.withAlpha(accent, 60), 8);
		Render2D.roundedRect(g, w[0], w[1], w[2], w[3], 8, Theme.panelBg());
		Render2D.gradientH(g, w[0] + 8, w[1], w[2] - 16, 1, accent, Theme.accent2());
		Render2D.text(g, "◈ Block Colors", w[0] + 10, w[1] + 9, Theme.TEXT, false);
		Render2D.text(g, "§8used by ESPs, ore holograms and Look Info", w[0] + 12 + Render2D.width("◈ Block Colors"), w[1] + 9, Theme.MUTED, false);
		int[] gr = grid();
		search.render(g, gr[0], w[1] + 22, gr[2], 14, Theme.FIELD);

		// grid
		int cols = gr[2] / CELL, rows = (filtered.size() + cols - 1) / cols;
		double max = Math.max(0, rows * CELL - gr[3]);
		scrollTarget = Math.max(-max, Math.min(0, scrollTarget));
		int sc = (int) scroll.setTarget(scrollTarget).get();
		g.enableScissor(gr[0], gr[1], gr[0] + gr[2], gr[1] + gr[3]);
		Entry hovered = null;
		for (int i = 0; i < filtered.size(); i++) {
			int cx = gr[0] + (i % cols) * CELL, cy = gr[1] + (i / cols) * CELL + sc;
			if (cy + CELL < gr[1] || cy > gr[1] + gr[3]) continue;
			Entry e = filtered.get(i);
			boolean sel = e.block == selected;
			boolean hov = Render2D.hovered(mx, my, cx, cy, CELL - 2, CELL - 2) && my >= gr[1] && my < gr[1] + gr[3];
			if (hov) hovered = e;
			Render2D.roundedRect(g, cx, cy, CELL - 2, CELL - 2, 4, sel ? ColorUtil.withAlpha(accent, 110) : hov ? 0x30FFFFFF : 0x14FFFFFF);
			if (!e.stack.isEmpty()) g.renderItem(e.stack, cx + 2, cy + 2);
			int c = BlockColors.get(e.block);
			Render2D.rect(g, cx + 3, cy + CELL - 5, CELL - 8, 2, c);
			if (BlockColors.isCustom(e.block)) Render2D.circle(g, cx + CELL - 5, cy + 3, 2, 0xFFFFFFFF);
		}
		g.disableScissor();

		// side panel
		int[] s = sv();
		int c = BlockColors.get(selected);
		Render2D.roundedRect(g, s[0], w[1] + 22, s[2] + 14, 36, 5, 0x18FFFFFF);
		ItemStack st = new ItemStack(selected.asItem());
		if (!st.isEmpty()) g.renderItem(st, s[0] + 4, w[1] + 32);
		Render2D.text(g, Render2D.trim(selected.getName().getString(), s[2] - 20), s[0] + 24, w[1] + 27, Theme.TEXT, false);
		Render2D.roundedRect(g, s[0] + 24, w[1] + 39, 40, 10, 3, c);
		Render2D.text(g, BlockColors.isCustom(selected) ? "§7custom" : "§8default", s[0] + 68, w[1] + 40, Theme.SUBTEXT, false);
		// SV square + hue
		int hueColor = ColorUtil.fromHsv(hue, 1, 1, 255);
		Render2D.gradientH(g, s[0], s[1], s[2], s[3], 0xFFFFFFFF, hueColor);
		Render2D.gradientV(g, s[0], s[1], s[2], s[3], 0x00000000, 0xFF000000);
		Render2D.circle(g, s[0] + (int) (sat * s[2]), s[1] + (int) ((1 - val) * s[3]), 3, 0xFFFFFFFF);
		int[] hb = hueBar();
		for (int i = 0; i < 6; i++) {
			int y1 = hb[1] + hb[3] * i / 6, y2 = hb[1] + hb[3] * (i + 1) / 6;
			Render2D.gradientV(g, hb[0], y1, hb[2], y2 - y1, ColorUtil.fromHsv(i / 6f, 1, 1, 255), ColorUtil.fromHsv((i + 1) / 6f, 1, 1, 255));
		}
		Render2D.rect(g, hb[0] - 1, hb[1] + (int) (hue * hb[3]) - 1, hb[2] + 2, 2, 0xFFFFFFFF);
		for (int i = 0; i < SWATCHES.length; i++) {
			int[] r = swatch(i);
			Render2D.roundedRect(g, r[0], r[1], r[2], r[3], 3, 0xFF3A3A48);
			Render2D.roundedRect(g, r[0] + 1, r[1] + 1, r[2] - 2, r[3] - 2, 2, SWATCHES[i]);
		}
		int[] hr = hexRect();
		hex.render(g, hr[0], hr[1], hr[2], hr[3], Theme.FIELD);
		button(g, mx, my, resetRect(), "Reset", Theme.FIELD);
		button(g, mx, my, linkRect(), (linkVariants ? "✔ " : "") + "Link ore + deepslate", linkVariants ? ColorUtil.withAlpha(accent, 120) : Theme.FIELD);
		button(g, mx, my, doneRect(), "Done", accent);
		if (hovered != null) {
			String tip = hovered.name + "  §8" + hovered.id;
			int tw = Render2D.width(tip) + 10;
			int tx = Math.min(mx + 10, width - tw - 4);
			Render2D.roundedRect(g, tx, my - 14, tw, 13, 4, 0xF0101016);
			Render2D.text(g, tip, tx + 5, my - 11, Theme.TEXT, false);
		}
	}

	private void button(GuiGraphics g, int mx, int my, int[] r, String text, int color) {
		boolean hover = Render2D.hovered(mx, my, r[0], r[1], r[2], r[3]);
		Render2D.roundedRect(g, r[0], r[1], r[2], r[3], 4, hover ? ColorUtil.brighten(color, 0.15f) : color);
		Render2D.centeredText(g, text, r[0] + r[2] / 2, r[1] + 3, 0xFFFFFFFF, false);
	}

	private void updateDrag(double mx, double my) {
		int[] s = sv();
		if (drag == 1) {
			sat = (float) Mth.clamp((mx - s[0]) / s[2], 0, 1);
			val = 1 - (float) Mth.clamp((my - s[1]) / s[3], 0, 1);
		} else if (drag == 2) {
			int[] hb = hueBar();
			hue = (float) Mth.clamp((my - hb[1]) / hb[3], 0, 0.999);
		}
		setColor(ColorUtil.fromHsv(hue, sat, val, 255));
	}

	// ---- Input --------------------------------------------------------------------------------
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		int[] w = win(), gr = grid(), s = sv(), hb = hueBar();
		if (Render2D.hovered(mx, my, gr[0], w[1] + 22, gr[2], 14)) {
			TextField.focused = search;
			if (event.button() == 1) search.setText("");
			return true;
		}
		if (mx >= gr[0] && mx < gr[0] + gr[2] && my >= gr[1] && my < gr[1] + gr[3]) {
			int cols = gr[2] / CELL;
			int i = (int) ((my - gr[1] - scroll.get()) / CELL) * cols + (int) ((mx - gr[0]) / CELL);
			if (i >= 0 && i < filtered.size()) {
				select(filtered.get(i).block);
				if (event.button() == 1) resetSelected();
				Sounds.click();
			}
			return true;
		}
		if (Render2D.hovered(mx, my, s[0], s[1], s[2], s[3])) drag = 1;
		else if (Render2D.hovered(mx, my, hb[0] - 1, hb[1], hb[2] + 2, hb[3])) drag = 2;
		if (drag != 0) {
			updateDrag(mx, my);
			return true;
		}
		for (int i = 0; i < SWATCHES.length; i++) {
			int[] r = swatch(i);
			if (Render2D.hovered(mx, my, r[0], r[1], r[2], r[3])) {
				setColor(SWATCHES[i]);
				select(selected);
				Sounds.click();
				return true;
			}
		}
		int[] hr = hexRect();
		if (Render2D.hovered(mx, my, hr[0], hr[1], hr[2], hr[3])) {
			TextField.focused = hex;
			return true;
		}
		if (hit(mx, my, resetRect())) {
			resetSelected();
			Sounds.click();
			return true;
		}
		if (hit(mx, my, linkRect())) {
			linkVariants = !linkVariants;
			Sounds.click();
			return true;
		}
		if (hit(mx, my, doneRect())) {
			onClose();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	private void resetSelected() {
		BlockColors.resetCustom(selected);
		Block v = linkVariants ? variant(selected) : null;
		if (v != null) BlockColors.resetCustom(v);
		ConfigManager.markDirty();
		select(selected);
	}

	private static boolean hit(double mx, double my, int[] r) { return Render2D.hovered(mx, my, r[0], r[1], r[2], r[3]); }

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		drag = 0;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double h, double v) {
		scrollTarget += v * CELL * 2;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			if (TextField.focused != null) TextField.focused = null;
			else onClose();
			return true;
		}
		if (TextField.focused != null && TextField.focused.keyPressed(event.key(), event.modifiers())) return true;
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		String s = event.codepointAsString();
		if (TextField.focused == null) TextField.focused = search;
		return !s.isEmpty() && TextField.focused.charTyped(s.charAt(0));
	}

	@Override
	public void onClose() {
		TextField.focused = null;
		ConfigManager.save();
		minecraft.setScreen(parent instanceof ClickGuiScreen ? new ClickGuiScreen() : parent);
	}

	@Override
	public boolean isPauseScreen() { return false; }
}
