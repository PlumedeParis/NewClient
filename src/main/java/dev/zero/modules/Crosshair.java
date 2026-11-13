package dev.zero.modules;

import dev.zero.module.Category;
import dev.zero.module.Module;
import dev.zero.render.Render2D;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.ColorSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.setting.PixelGridSetting;
import dev.zero.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphics;

/** Custom pixel crosshair, designed in the crosshair editor. Purely cosmetic. */
public class Crosshair extends Module {
	public static final String[][] PRESETS = {
		{"Cross",
			"...............", "...............", "...............", "...............", "...............",
			".......#.......", ".......#.......", ".....#####.....", ".......#.......", ".......#......."},
		{"Gap Cross",
			"...............", "...............", ".......#.......", ".......#.......", ".......#.......",
			"...............", "...............", "..###..#..###..", "...............", "...............",
			".......#.......", ".......#.......", ".......#......."},
		{"Dot",
			"...............", "...............", "...............", "...............", "...............",
			"...............", "......###......", "......###......", "......###......"},
		{"Circle",
			"...............", "...............", "...............", "...............", "......###......",
			".....#...#.....", "....#.....#....", "....#..#..#....", "....#.....#....", ".....#...#.....", "......###......"},
		{"X",
			"...............", "...............", "...............", "...............", "....#.....#....",
			".....#...#.....", "......#.#......", ".......#.......", "......#.#......", ".....#...#.....", "....#.....#...."},
		{"T",
			"...............", "...............", "...............", "...............", "...............",
			"...............", "...............", "...###.#.###...", "...............", ".......#.......", ".......#.......", ".......#......."},
		{"Square",
			"...............", "...............", "...............", "...............", "...............",
			".....#####.....", ".....#...#.....", ".....#.#.#.....", ".....#...#.....", ".....#####....."},
	};

	public final PixelGridSetting pixels = add(new PixelGridSetting("Design", "Your crosshair, click to open the editor", gapCross()));
	public final ColorSetting color = add(new ColorSetting("Color", "Crosshair color", 0xFFFFFFFF, true));
	public final BoolSetting outline = add(new BoolSetting("Outline", "Dark outline for contrast", true));
	public final ColorSetting outlineColor = add(new ColorSetting("Outline Color", "Outline color", 0xC0000000, true)).visibleWhen(outline::get);
	public final NumberSetting scale = add(new NumberSetting("Scale", "Size of one design pixel (GUI pixels)", 1, 0.5, 3, 0.5, "x"));
	private final BoolSetting targetColor = add(new BoolSetting("Target Color", "Change color when aiming at an entity", true));
	private final ColorSetting target = add(new ColorSetting("Target", "Color when aiming at an entity", 0xFFFF4D6D, true)).visibleWhen(targetColor::get);
	private final BoolSetting attackIndicator = add(new BoolSetting("Attack Indicator", "Cooldown bar under the crosshair", true));

	public Crosshair() {
		super("Crosshair", "Custom crosshair with a pixel editor", Category.MISC);
	}

	private static boolean[] gapCross() {
		String[] p = PRESETS[1];
		String[] rows = new String[p.length - 1];
		System.arraycopy(p, 1, rows, 0, rows.length);
		return PixelGridSetting.fromRows(rows);
	}

	public static boolean[] preset(int i) {
		String[] p = PRESETS[i];
		String[] rows = new String[p.length - 1];
		System.arraycopy(p, 1, rows, 0, rows.length);
		return PixelGridSetting.fromRows(rows);
	}

	/** Draws a design centered at (cx, cy) with each pixel {@code px} GUI pixels wide. */
	public static void draw(GuiGraphics g, PixelGridSetting grid, float cx, float cy, float px, int color, boolean outline, int outlineColor) {
		int n = PixelGridSetting.SIZE, half = n / 2;
		g.pose().pushMatrix();
		g.pose().translate(cx - px * (half + 0.5f), cy - px * (half + 0.5f));
		g.pose().scale(px, px);
		if (outline) {
			for (int y = -1; y <= n; y++)
				for (int x = -1; x <= n; x++) {
					if (inside(grid, x, y)) continue;
					if (inside(grid, x - 1, y) || inside(grid, x + 1, y) || inside(grid, x, y - 1) || inside(grid, x, y + 1))
						g.fill(x, y, x + 1, y + 1, outlineColor);
				}
		}
		for (int y = 0; y < n; y++)
			for (int x = 0; x < n; x++)
				if (grid.get(x, y)) g.fill(x, y, x + 1, y + 1, color);
		g.pose().popMatrix();
	}

	private static boolean inside(PixelGridSetting grid, int x, int y) {
		return x >= 0 && y >= 0 && x < PixelGridSetting.SIZE && y < PixelGridSetting.SIZE && grid.get(x, y);
	}

	/** Replaces the vanilla crosshair HUD element. */
	public void renderCrosshair(GuiGraphics g) {
		if (!mc.options.getCameraType().isFirstPerson() || mc.player == null) return;
		if (mc.gameMode != null && mc.gameMode.getPlayerMode() == net.minecraft.world.level.GameType.SPECTATOR && mc.crosshairPickEntity == null) return;
		float cx = g.guiWidth() / 2f, cy = g.guiHeight() / 2f;
		int c = targetColor.get() && mc.crosshairPickEntity != null ? target.get() : color.get();
		draw(g, pixels, cx, cy, scale.getFloat(), c, outline.get(), outlineColor.get());
		if (attackIndicator.get()) {
			float s = mc.player.getAttackStrengthScale(0);
			if (s < 1) {
				int w = 16, y = (int) (cy + PixelGridSetting.SIZE * scale.getFloat() / 2f + 3);
				Render2D.rect(g, (int) cx - w / 2, y, w, 2, 0x80000000);
				Render2D.rect(g, (int) cx - w / 2, y, (int) (w * s), 2, ColorUtil.withAlpha(c, 230));
			}
		}
	}
}
