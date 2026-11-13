package dev.zero.gui;

import dev.zero.config.ConfigManager;
import dev.zero.hud.Notifications;
import dev.zero.module.Module;
import dev.zero.module.ModuleManager;
import dev.zero.render.Render2D;
import dev.zero.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** One-click setups: turns on exactly the modules for what you are doing, and the rest off. */
public class SetupScreen extends Screen {
	public record Preset(String icon, String title, String description, int color, Supplier<List<Module>> modules) {}

	public static final List<Preset> PRESETS = List.of(
		new Preset("⌖", "DonutSMP hunt", "DonutHunter with everything it needs: spawners, storage, hidden bases, live leads while you mine.", 0xFFFF4DD8,
			() -> List.of(ModuleManager.donutHunter, ModuleManager.fullbright)),
		new Preset("★", "2b2t base hunt", "BaseFinder, SusChunk, StorageESP, every sign finder, ranked by BaseRadar. PlayerDetector watches your back.", 0xFFFFB347,
			() -> List.of(ModuleManager.baseRadar, ModuleManager.baseFinder, ModuleManager.susChunk, ModuleManager.storageEsp, ModuleManager.spawnerFinder,
				ModuleManager.amethystFinder, ModuleManager.growthFinder, ModuleManager.rotatedDeepslate, ModuleManager.tunnelFinder, ModuleManager.playerDetector)),
		new Preset("⬢", "Mining", "SeedOreFinder holograms, SafeMiner routes and Fullbright. Type the seed in SeedOreFinder first.", 0xFF4FD1C5,
			() -> List.of(ModuleManager.seedOreFinder, ModuleManager.safeMiner, ModuleManager.fullbright, ModuleManager.playerDetector)),
		new Preset("◉", "Looting", "StorageESP, SpawnerESP and BlockESP to empty a place you found.", 0xFF8B7CFF,
			() -> List.of(ModuleManager.storageEsp, ModuleManager.spawnerEsp, ModuleManager.blockEsp, ModuleManager.fullbright, ModuleManager.playerDetector)),
		new Preset("✖", "Everything off", "Turns every module off. Your settings are kept.", 0xFF9CA3AF, List::of));

	private final Screen parent;

	public SetupScreen(Screen parent) {
		super(Component.literal("Setup"));
		this.parent = parent;
	}

	/** Turns on exactly this preset's modules (the crosshair is left as it is). */
	public static void apply(Preset p) {
		List<Module> want = new ArrayList<>(p.modules().get());
		for (Module m : ModuleManager.all()) {
			if (m == ModuleManager.crosshair) continue;
			if (m.isEnabled() && !want.contains(m) && m != ModuleManager.donutHunter) m.setEnabledSilently(false);
		}
		if (ModuleManager.donutHunter.isEnabled() && !want.contains(ModuleManager.donutHunter)) ModuleManager.donutHunter.setEnabledSilently(false);
		for (Module m : want) if (!m.isEnabled()) m.setEnabledSilently(true);
		ConfigManager.markDirty();
		Notifications.push("Setup: " + p.title(), want.isEmpty() ? "All modules off" : want.size() + " modules on", Notifications.Type.INFO, 2500);
	}

	private int[] window() {
		int w = Math.min(width - 20, 380), h = 34 + PRESETS.size() * step();
		return new int[] {width / 2 - w / 2, Math.max(4, height / 2 - h / 2), w, h};
	}

	/** Card pitch: shrinks a little on small windows so all setups fit. */
	private int step() { return Math.max(34, Math.min(42, (height - 44) / PRESETS.size())); }

	private int[] card(int i) {
		int[] w = window();
		return new int[] {w[0] + 8, w[1] + 28 + i * step(), w[2] - 16, step() - 4};
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fillGradient(0, 0, width, height, 0xA005050A, 0xC00B0B18);
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float partialTick) {
		super.render(g, mx, my, partialTick);
		int[] win = window();
		int x = win[0], y = win[1], w = win[2], h = win[3];
		Render2D.glow(g, x, y, w, h, 8, ColorUtil.withAlpha(Theme.accent(), 60), 8);
		Render2D.roundedRect(g, x, y, w, h, 8, Theme.panelBg());
		Render2D.gradientH(g, x + 8, y, w - 16, 1, Theme.accent(), Theme.accent2());
		Render2D.text(g, "✦ Setup  §7What are you doing? One click sets the modules.", x + 9, y + 10, Theme.TEXT, false);
		for (int i = 0; i < PRESETS.size(); i++) {
			Preset p = PRESETS.get(i);
			int[] c = card(i);
			boolean hover = Render2D.hovered(mx, my, c[0], c[1], c[2], c[3]);
			Render2D.roundedRect(g, c[0], c[1], c[2], c[3], 6, hover ? 0xFF22222E : Theme.FIELD);
			if (hover) Render2D.glow(g, c[0], c[1], c[2], c[3], 6, ColorUtil.withAlpha(p.color(), 70), 5);
			Render2D.roundedRect(g, c[0] + 6, c[1] + 6, 26, 26, 6, ColorUtil.withAlpha(p.color(), 50));
			Render2D.centeredText(g, p.icon(), c[0] + 19, c[1] + 15, p.color(), false);
			Render2D.text(g, p.title(), c[0] + 40, c[1] + 6, Theme.TEXT, false);
			List<String> lines = wrap(p.description(), c[2] - 48);
			for (int l = 0; l < Math.min(2, lines.size()); l++) Render2D.text(g, lines.get(l), c[0] + 40, c[1] + 17 + l * 9, Theme.SUBTEXT, false);
		}
	}

	private static List<String> wrap(String text, int width) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (line.length() > 0 && Render2D.width(line + " " + word) > width) {
				lines.add(line.toString());
				line = new StringBuilder();
			}
			if (line.length() > 0) line.append(' ');
			line.append(word);
		}
		if (line.length() > 0) lines.add(line.toString());
		return lines;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = 0; i < PRESETS.size(); i++) {
			int[] c = card(i);
			if (Render2D.hovered(event.x(), event.y(), c[0], c[1], c[2], c[3])) {
				apply(PRESETS.get(i));
				dev.zero.util.Sounds.click();
				onClose();
				return true;
			}
		}
		int[] w = window();
		if (!Render2D.hovered(event.x(), event.y(), w[0], w[1], w[2], w[3])) {
			onClose();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent instanceof ClickGuiScreen ? new ClickGuiScreen() : parent);
	}

	@Override
	public boolean isPauseScreen() { return false; }
}
