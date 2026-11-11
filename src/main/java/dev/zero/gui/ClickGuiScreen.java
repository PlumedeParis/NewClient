package dev.zero.gui;

import com.google.gson.JsonObject;
import dev.zero.config.ClientSettings;
import dev.zero.config.ConfigManager;
import dev.zero.gui.comp.Comp;
import dev.zero.gui.comp.KeybindComp;
import dev.zero.gui.comp.ModuleButton;
import dev.zero.gui.comp.SettingComp;
import dev.zero.gui.comp.TextField;
import dev.zero.hud.Hud;
import dev.zero.module.Category;
import dev.zero.module.Module;
import dev.zero.module.ModuleManager;
import dev.zero.render.Render2D;
import dev.zero.setting.Setting;
import dev.zero.util.Anim;
import dev.zero.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The main ClickGUI. */
public class ClickGuiScreen extends Screen {
	private static final Map<String, int[]> LAYOUT = new HashMap<>();
	private static String query = "";

	private final List<Panel> panels = new ArrayList<>();
	private final TextField search;
	private final Anim openAnim = new Anim(0, 10);
	private final Anim scroll = new Anim(0, 14);
	private double scrollTarget;
	private final long openedAt = System.currentTimeMillis();

	public ClickGuiScreen() {
		super(Component.literal("ZER\u00D8"));
		search = new TextField("Search modules...  (just start typing)", 32, null, t -> query = t.toLowerCase(Locale.ROOT).trim());
		search.setText(query);
		openAnim.setTarget(1);
		int i = 0;
		for (Category c : Category.values()) {
			List<Comp> comps = new ArrayList<>();
			for (Module m : ModuleManager.byCategory(c)) comps.add(new ModuleButton(m));
			panels.add(makePanel(c.name(), c.title, c.icon, c.color, comps, i++));
		}
		List<Comp> client = new ArrayList<>();
		for (Setting<?> s : ClientSettings.SETTINGS) client.add(SettingComp.of(s));
		panels.add(makePanel("CLIENT", "Client", "⚙", 0xFFB4B4C8, client, i));
	}

	private static Panel makePanel(String id, String title, String icon, int color, List<Comp> comps, int index) {
		int[] saved = LAYOUT.get(id);
		Panel p = new Panel(id, title, icon, color, comps, saved != null ? saved[0] : 16 + index * (Panel.WIDTH + 12), saved != null ? saved[1] : 42);
		if (saved != null) p.collapsed = saved[2] == 1;
		return p;
	}

	public static String searchQuery() { return query; }

	@Override
	protected void init() {
		// Keep every panel reachable: wrap default positions and clamp saved ones to the window.
		int row = 0, col = 0;
		for (Panel p : panels) {
			if (!LAYOUT.containsKey(p.id)) {
				int x = 16 + col * (Panel.WIDTH + 12);
				if (x + Panel.WIDTH > width - 8 && col > 0) {
					row++;
					col = 0;
					x = 16;
				}
				p.x = x;
				p.y = 42 + row * 150;
				col++;
			}
			p.x = Math.max(0, Math.min(p.x, width - Panel.WIDTH));
			p.y = Math.max(36, Math.min(p.y, height - 30));
		}
	}

	// ---- Layout persistence --------------------------------------------------------------
	public static void loadLayout(JsonObject o) {
		for (String key : o.keySet()) {
			JsonObject p = o.getAsJsonObject(key);
			LAYOUT.put(key, new int[] {p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("collapsed").getAsBoolean() ? 1 : 0});
		}
	}

	public static JsonObject saveLayout() {
		JsonObject o = new JsonObject();
		for (Map.Entry<String, int[]> e : LAYOUT.entrySet()) {
			JsonObject p = new JsonObject();
			p.addProperty("x", e.getValue()[0]);
			p.addProperty("y", e.getValue()[1]);
			p.addProperty("collapsed", e.getValue()[2] == 1);
			o.add(e.getKey(), p);
		}
		return o;
	}

	private void storeLayout() {
		for (Panel p : panels) LAYOUT.put(p.id, new int[] {p.x, p.y, p.collapsed ? 1 : 0});
	}

	// ---- Rendering ------------------------------------------------------------------------
	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		float a = openAnim.getF();
		if (ClientSettings.BLUR.get() && minecraft != null && minecraft.options.getMenuBackgroundBlurriness() >= 1) g.blurBeforeThisStratum();
		g.fillGradient(0, 0, width, height, ColorUtil.withAlpha(0x05050A, (int) (110 * a)), ColorUtil.withAlpha(0x0B0B18, (int) (170 * a)));
		// Subtle accent vignette at the bottom.
		g.fillGradient(0, height - 80, width, height, 0x00000000, ColorUtil.withAlpha(ClientSettings.accent(), (int) (28 * a)));
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float partialTick) {
		super.render(g, mx, my, partialTick);
		float open = openAnim.getF();
		int sc = (int) scroll.setTarget(scrollTarget).get();

		Panel top = null;
		for (int i = 0; i < panels.size(); i++) {
			Panel p = panels.get(i);
			if (p.isDragging()) {
				top = p;
				continue;
			}
			if (!query.isEmpty() && !p.hasVisible()) continue;
			float appear = (float) Anim.easeOutCubic(Math.min(1, Math.max(0, (System.currentTimeMillis() - openedAt - i * 45) / 260.0)));
			g.pose().pushMatrix();
			g.pose().translate(0, (1 - appear) * -18);
			p.render(g, mx, my, sc, appear);
			g.pose().popMatrix();
		}
		if (top != null) top.render(g, mx, my, sc, 1);

		renderTopBar(g, mx, my, open);
		renderTooltip(g, mx, my, sc);
	}

	private int[] topBar() {
		int w = 320, h = 22;
		return new int[] {width / 2 - w / 2, 10, w, h};
	}

	private int[] findsButton() {
		int[] t = topBar();
		return new int[] {t[0] + t[2] - 58, t[1] + 4, 52, 14};
	}

	private void renderTopBar(GuiGraphics g, int mx, int my, float open) {
		int[] t = topBar();
		int x = t[0], y = (int) (t[1] - (1 - open) * 20), w = t[2], h = t[3];
		Render2D.glow(g, x, y, w, h, 7, ColorUtil.withAlpha(ClientSettings.accent(), 60), 6);
		Render2D.roundedRect(g, x, y, w, h, 7, Theme.headerBg());
		Render2D.gradientH(g, x + 8, y, w - 16, 1, ClientSettings.accent(), ClientSettings.accent2());
		Render2D.gradientText(g, Hud.NAME, x + 9, y + 7, i -> ClientSettings.accentAt(i / 9f), true);
		int sx = x + 14 + Render2D.width(Hud.NAME);
		search.render(g, sx, y + 4, w - (sx - x) - 66, 14, Theme.FIELD);
		int[] b = findsButton();
		boolean hover = Render2D.hovered(mx, my, b[0], b[1], b[2], b[3]);
		Render2D.roundedRect(g, b[0], y + 4, b[2], b[3], 4, hover ? ClientSettings.accent() : ColorUtil.withAlpha(ClientSettings.accent(), 70));
		Render2D.centeredText(g, "★ Finds", b[0] + b[2] / 2, y + 7, 0xFFFFFFFF, false);
	}

	private void renderTooltip(GuiGraphics g, int mx, int my, int sc) {
		String tip = null;
		for (int i = panels.size() - 1; i >= 0 && tip == null; i--) tip = panels.get(i).tooltip(mx, my, sc);
		if (tip == null) {
			if (Render2D.hovered(mx, my, findsButton()[0], findsButton()[1], findsButton()[2], findsButton()[3]))
				tip = "Saved finds  §7Bases, stashes and sus chunks found on this server";
			else tip = "§7Left: toggle  •  Right: settings  •  Middle: bind  •  Drag headers  •  Scroll";
		}
		tip = Render2D.trim(tip, width - 30);
		int w = Render2D.width(tip) + 16;
		int x = width / 2 - w / 2, y = height - 22;
		Render2D.roundedRect(g, x, y, w, 15, 5, 0xD0101016);
		Render2D.text(g, tip, x + 8, y + 4, Theme.TEXT, false);
	}

	// ---- Input ----------------------------------------------------------------------------
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		int button = event.button();
		int[] t = topBar();
		int[] b = findsButton();
		if (Render2D.hovered(mx, my, b[0], b[1], b[2], b[3])) {
			minecraft.setScreen(new FindsScreen(this));
			return true;
		}
		if (Render2D.hovered(mx, my, t[0], t[1], t[2], t[3])) {
			TextField.focused = search;
			if (button == 1) search.setText("");
			return true;
		}
		TextField.focused = null;
		int sc = (int) scroll.get();
		for (int i = panels.size() - 1; i >= 0; i--) {
			Panel p = panels.get(i);
			if (!query.isEmpty() && !p.hasVisible()) continue;
			if (p.mouseClicked(mx, my, button, sc)) {
				// bring to front
				panels.remove(i);
				panels.add(p);
				return true;
			}
		}
		KeybindComp.listening = null;
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		for (Panel p : panels) p.mouseReleased(event.x(), event.y(), event.button());
		storeLayout();
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		scrollTarget += vertical * 24;
		scrollTarget = Math.min(0, Math.max(-2000, scrollTarget));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (KeybindComp.listening != null) {
			for (Panel p : panels) for (Comp c : p.comps) if (c.keyPressed(key)) return true;
			KeybindComp.listening = null;
			return true;
		}
		if (TextField.focused == null && key == GLFW.GLFW_KEY_V && ((event.modifiers() & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0 || TextField.ctrlDown())) {
			TextField.focused = search;
			search.insert(minecraft.keyboardHandler.getClipboard());
			return true;
		}
		if (TextField.focused != null) {
			if (key == GLFW.GLFW_KEY_ESCAPE) {
				TextField.focused = null;
				return true;
			}
			if (TextField.focused.keyPressed(key, event.modifiers())) return true;
		}
		if (key == ClientSettings.GUI_KEY.get() && TextField.focused == null) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		String s = event.codepointAsString();
		if (s.isEmpty()) return false;
		char c = s.charAt(0);
		if (TextField.focused == null && KeybindComp.listening == null && c > 32) TextField.focused = search;
		if (TextField.focused != null) return TextField.focused.charTyped(c);
		return false;
	}

	@Override
	public boolean isPauseScreen() { return false; }

	@Override
	public void removed() {
		TextField.focused = null;
		KeybindComp.listening = null;
		storeLayout();
		ConfigManager.save();
	}
}
