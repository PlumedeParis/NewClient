package dev.newclient.gui.comp;

import dev.newclient.config.ConfigManager;
import dev.newclient.gui.ClickGuiScreen;
import dev.newclient.gui.Theme;
import dev.newclient.module.Module;
import dev.newclient.render.Render2D;
import dev.newclient.setting.Setting;
import dev.newclient.util.Anim;
import dev.newclient.util.ColorUtil;
import dev.newclient.util.Sounds;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A module row: left click toggles, right click expands its settings, middle click binds. */
public class ModuleButton extends Comp {
	private static final int ROW = 17;
	private final Module module;
	private final List<Comp> children = new ArrayList<>();
	private final Anim expand = new Anim(0, 14);
	private final Anim enabledAnim;
	private final Anim hoverAnim = new Anim(0, 20);
	private final Switch sw;
	private boolean expanded;

	public ModuleButton(Module module) {
		this.module = module;
		for (Setting<?> s : module.getSettings()) children.add(SettingComp.of(s));
		children.add(new KeybindComp(module.keybind));
		enabledAnim = new Anim(module.isEnabled() ? 1 : 0, 12);
		sw = new Switch(module.isEnabled());
	}

	public Module getModule() { return module; }

	@Override
	public boolean visible() {
		String q = ClickGuiScreen.searchQuery();
		return q.isEmpty() || module.getName().toLowerCase(Locale.ROOT).contains(q) || module.getDescription().toLowerCase(Locale.ROOT).contains(q);
	}

	private int childrenHeight() {
		int h = 0;
		for (Comp c : children) if (c.visible()) h += c.height();
		return h + 2;
	}

	@Override
	public int height() {
		return ROW + (int) (childrenHeight() * expand.getF());
	}

	@Override
	protected void render(GuiGraphics g, int mx, int my) {
		expand.setTarget(expanded ? 1 : 0);
		boolean hover = mx >= x && mx < x + w && my >= y && my < y + ROW;
		hoverAnim.setTarget(hover ? 1 : 0);
		float on = enabledAnim.setTarget(module.isEnabled() ? 1 : 0).getF();
		float hv = hoverAnim.getF();

		if (on > 0.01f) Render2D.gradientH(g, x, y, w, ROW, ColorUtil.withAlpha(Theme.accent(), (int) (70 * on)), ColorUtil.withAlpha(Theme.accent2(), (int) (10 * on)));
		if (hv > 0.01f) Render2D.rect(g, x, y, w, ROW, ColorUtil.withAlpha(0xFFFFFF, (int) (22 * hv)));
		if (on > 0.01f) Render2D.rect(g, x, y + 3, 2, (int) ((ROW - 6) * on), Theme.accent());

		int textColor = ColorUtil.lerp(Theme.SUBTEXT, Theme.TEXT, Math.max(on, hv * 0.6f));
		Render2D.text(g, module.getName(), x + 7 + (int) (2 * hv), y + 5, textColor, false);
		sw.render(g, x + w - 22, y + 5, module.isEnabled());
		if (module.keybind.isBound()) {
			String k = module.keybind.display();
			Render2D.text(g, k, x + w - 26 - Render2D.width(k), y + 5, Theme.MUTED, false);
		}

		float e = expand.getF();
		if (e > 0.005f) {
			int ch = (int) (childrenHeight() * e);
			int top = y + ROW;
			Render2D.rect(g, x, top, w, ch, Theme.settingsBg());
			Render2D.rect(g, x + 2, top + 1, 1, Math.max(0, ch - 2), ColorUtil.withAlpha(Theme.accent(), 120));
			g.enableScissor(x, top, x + w, top + ch);
			int cy = top + 1;
			for (Comp c : children) {
				if (!c.visible()) continue;
				c.draw(g, x + 3, cy, w - 3, mx, my);
				cy += c.height();
			}
			g.disableScissor();
		}
	}

	private boolean childrenInteractive() { return expanded && expand.getF() > 0.95f; }

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (mx < x || mx >= x + w) return false;
		if (my >= y && my < y + ROW) {
			switch (button) {
				case 0 -> {
					module.toggle();
					Sounds.click();
					ConfigManager.markDirty();
				}
				case 1 -> expanded = !expanded;
				case 2 -> {
					for (Comp c : children) if (c instanceof KeybindComp k) KeybindComp.listening = k;
					expanded = true;
				}
				default -> {}
			}
			return true;
		}
		if (childrenInteractive()) {
			for (Comp c : children) if (c.visible() && c.mouseClicked(mx, my, button)) return true;
		}
		return false;
	}

	@Override
	public void mouseReleased(double mx, double my, int button) {
		for (Comp c : children) c.mouseReleased(mx, my, button);
	}

	@Override
	public boolean keyPressed(int key) {
		for (Comp c : children) if (c.keyPressed(key)) return true;
		return false;
	}

	@Override
	public boolean charTyped(char ch) {
		for (Comp c : children) if (c.charTyped(ch)) return true;
		return false;
	}

	@Override
	public String tooltip(double mx, double my) {
		if (mx >= x && mx < x + w && my >= y && my < y + ROW)
			return module.getName() + "  §7" + module.getDescription() + "  §8(right click: settings, middle click: bind)";
		if (childrenInteractive()) {
			for (Comp c : children) {
				if (!c.visible()) continue;
				String t = c.tooltip(mx, my);
				if (t != null) return t;
			}
		}
		return null;
	}
}
