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
		if (!LAYOUT.isEmpty() && !LAYOUT.containsKey("SIGNS")) LAYOUT.clear(); // layout from before the category rework
		int i = 0;
		for (Category c : Category.values()) {
			List<Comp> comps = new ArrayList<>();
			for (Module m : ModuleManager.byCategory(c)) comps.add(new ModuleButton(m));
			panels.add(makePanel(c.name(), c.title, c.icon, c.color, comps, i++));
		}
		List<Comp> client = new ArrayList<>();
		List<Comp> group = null;
		for (Setting<?> s : ClientSettings.SETTINGS) {
			if (s instanceof dev.zero.setting.SectionSetting sec) {
				group = new ArrayList<>();
				client.add(new dev.zero.gui.comp.SectionComp(sec.getName(), group, sec.get()));
			} else (group != null ? group : client).add(SettingComp.of(s));
		}
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
		// Panels without a saved position are packed into tidy columns (shortest column first).
		int cols = Math.max(1, (width - 20) / (Panel.WIDTH + 10));
		int used = Math.min(cols, panels.size());
		int left = Math.max(8, (width - (used * (Panel.WIDTH + 10) - 10)) / 2);
		int[] colY = new int[used];
		java.util.Arrays.fill(colY, 40);
		for (Panel p : panels) {
			if (!LAYOUT.containsKey(p.id)) {
				int best = 0;
				for (int c = 1; c < used; c++) if (colY[c] < colY[best]) best = c;
				p.x = left + best * (Panel.WIDTH + 10);
				p.y = colY[best];
				colY[best] += p.fullHeight() + 10;
			}
			p.x = Math.max(0, Math.min(p.x, width - Panel.WIDTH));
			p.y = Math.max(36, p.y);
		}
	}

	/** Forget every saved panel position: the next open packs them neatly again. */
	public static void tidy() {
		LAYOUT.clear();
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.screen instanceof ClickGuiScreen) mc.setScreen(new ClickGuiScreen());
	}

	// ---- Layout persistence --------------------------------------------------------------
	public static void clearLayout() { LAYOUT.clear(); }

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
		int w = Math.min(width - 16, 560), h = 22;
		return new int[] {width / 2 - w / 2, 10, w, h};
	}

	private static final String[] TOP_ICONS = {"\u2605", "\u2630", "\u25A3", "!", "\u2726"};
	private static final String[] TOP_LABELS = {"Finds", "Profiles", "HUD", "Alerts", "Setup"};
	private static final int[] TOP_FULL = {52, 62, 44, 54, 52};

	/** On a narrow window the buttons show only their icon so the search box keeps its room. */
	private boolean compactTop() { return topBar()[2] < 500; }

	private int topWidth(int i) { return compactTop() ? 18 : TOP_FULL[i]; }

	/** Buttons on the right of the top bar, right to left: Finds, Profiles, HUD, Alerts, Setup. */
	private int[] topButton(int i) {
		int[] t = topBar();
		int x = t[0] + t[2] - 6;
		for (int k = 0; k <= i; k++) x -= topWidth(k) + (k > 0 ? 4 : 0);
		return new int[] {x, t[1] + 4, topWidth(i), 14};
	}

	private int[] findsButton() { return topButton(0); }

	private void renderTopBar(GuiGraphics g, int mx, int my, float open) {
		int[] t = topBar();
		int x = t[0], y = (int) (t[1] - (1 - open) * 20), w = t[2], h = t[3];
		Render2D.glow(g, x, y, w, h, 7, ColorUtil.withAlpha(ClientSettings.accent(), 60), 6);
		Render2D.roundedRect(g, x, y, w, h, 7, Theme.headerBg());
		Render2D.gradientH(g, x + 8, y, w - 16, 1, ClientSettings.accent(), ClientSettings.accent2());
		Render2D.gradientText(g, Hud.NAME, x + 9, y + 7, i -> ClientSettings.accentAt(i / 9f), true);
		int sx = x + 14 + Render2D.width(Hud.NAME);
		int lastButton = topButton(TOP_ICONS.length - 1)[0];
		search.render(g, sx, y + 4, lastButton - 6 - sx, 14, Theme.FIELD);
		for (int i = 0; i < TOP_ICONS.length; i++) {
			int[] b = topButton(i);
			boolean hover = Render2D.hovered(mx, my, b[0], b[1], b[2], b[3]);
			Render2D.roundedRect(g, b[0], y + 4, b[2], b[3], 4, hover ? ClientSettings.accent() : ColorUtil.withAlpha(ClientSettings.accent(), 70));
			Render2D.centeredText(g, compactTop() ? TOP_ICONS[i] : TOP_ICONS[i] + " " + TOP_LABELS[i], b[0] + b[2] / 2, y + 7, 0xFFFFFFFF, false);
			int unread = dev.zero.hud.Notifications.unread();
			if (i == 3 && unread > 0) { // unread alerts badge
				String n = unread > 99 ? "99+" : String.valueOf(unread);
				int bw = Render2D.width(n) + 6;
				Render2D.roundedRect(g, b[0] + b[2] - bw / 2 - 2, y, bw, 9, 4, 0xFFFF4D6D);
				Render2D.centeredText(g, n, b[0] + b[2] - 2, y + 1, 0xFFFFFFFF, false);
			}
		}
	}

	private void renderTooltip(GuiGraphics g, int mx, int my, int sc) {
		String tip = null;
		for (int i = panels.size() - 1; i >= 0 && tip == null; i--) tip = panels.get(i).tooltip(mx, my, sc);
		if (tip == null) {
			if (Render2D.hovered(mx, my, findsButton()[0], findsButton()[1], findsButton()[2], findsButton()[3]))
				tip = "Saved finds  §7Bases, stashes and sus chunks found on this server";
			else if (Render2D.hovered(mx, my, topButton(1)[0], topButton(1)[1], topButton(1)[2], topButton(1)[3]))
				tip = "Profiles  §7Active: " + dev.zero.config.ConfigManager.activeProfile() + " • separate setups per server";
			else if (Render2D.hovered(mx, my, topButton(2)[0], topButton(2)[1], topButton(2)[2], topButton(2)[3]))
				tip = "HUD editor  §7Drag the HUD panels anywhere";
			else if (Render2D.hovered(mx, my, topButton(3)[0], topButton(3)[1], topButton(3)[2], topButton(3)[3]))
				tip = "Alerts  §7Everything ZER\u00D8 found, including what didn't pop up";
			else if (Render2D.hovered(mx, my, topButton(4)[0], topButton(4)[1], topButton(4)[2], topButton(4)[3]))
				tip = "Setup  §7One click: turn on the right modules for what you're doing";
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
		int[] pb = topButton(1), hb = topButton(2);
		if (Render2D.hovered(mx, my, pb[0], pb[1], pb[2], pb[3])) {
			minecraft.setScreen(new ProfilesScreen(this));
			return true;
		}
		if (Render2D.hovered(mx, my, hb[0], hb[1], hb[2], hb[3])) {
			minecraft.setScreen(new HudEditorScreen(null));
			return true;
		}
		int[] ab = topButton(3), sb = topButton(4);
		if (Render2D.hovered(mx, my, ab[0], ab[1], ab[2], ab[3])) {
			minecraft.setScreen(new AlertsScreen(this));
			return true;
		}
		if (Render2D.hovered(mx, my, sb[0], sb[1], sb[2], sb[3])) {
			minecraft.setScreen(new SetupScreen(this));
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
