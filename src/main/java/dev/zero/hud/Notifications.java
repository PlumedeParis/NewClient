package dev.zero.hud;

import dev.zero.config.ClientSettings;
import dev.zero.render.Render2D;
import dev.zero.util.Anim;
import dev.zero.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Animated toast notifications in the bottom-right corner. Purely visual. */
public final class Notifications {
	public enum Type {
		ENABLED(0xFF4ADE80, "✔"),
		DISABLED(0xFFF87171, "✖"),
		INFO(0xFF60A5FA, "i"),
		WARNING(0xFFFBBF24, "!"),
		ALERT(0xFFF472B6, "⚠"),
		FOUND(0xFFA78BFA, "★");

		final int color;
		final String icon;

		Type(int color, String icon) {
			this.color = color;
			this.icon = icon;
		}
	}

	private static final class Toast {
		final String title, message;
		final Type type;
		final long created = System.currentTimeMillis();
		final long duration;
		final Anim slide = new Anim(0, 14);
		final Anim y = new Anim(-1, 16);
		boolean leaving;

		Toast(String title, String message, Type type, long duration) {
			this.title = title;
			this.message = message;
			this.type = type;
			this.duration = duration;
			slide.setTarget(1);
		}
	}

	private static final List<Toast> TOASTS = new ArrayList<>();
	private static final int MAX = 6;

	private Notifications() {}

	public static void push(String title, String message, Type type) {
		boolean isToggle = type == Type.ENABLED || type == Type.DISABLED;
		if (isToggle && !ClientSettings.TOGGLE_TOASTS.get()) return;
		push(title, message, type, isToggle ? 1800 : 4500);
	}

	public static synchronized void push(String title, String message, Type type, long durationMs) {
		if (!ClientSettings.NOTIFICATIONS.get()) return;
		// Collapse spammy duplicates of a toggle.
		TOASTS.removeIf(t -> t.title.equals(title) && (t.type == Type.ENABLED || t.type == Type.DISABLED) && (type == Type.ENABLED || type == Type.DISABLED));
		TOASTS.add(new Toast(title, message, type, durationMs));
		while (TOASTS.size() > MAX) TOASTS.remove(0);
	}

	public static synchronized void render(GuiGraphics g) {
		int sw = g.guiWidth(), sh = g.guiHeight();
		int[] anchor = Hud.notificationAnchor(sw, sh); // also records the area for the HUD editor
		if (TOASTS.isEmpty()) return;
		long now = System.currentTimeMillis();
		int h = 30, gap = 5;
		int rightX = anchor[0], baseY = anchor[1];
		boolean up = anchor[2] == 1;
		int index = 0;
		for (Iterator<Toast> it = TOASTS.iterator(); it.hasNext(); ) {
			Toast t = it.next();
			long age = now - t.created;
			if (!t.leaving && age > t.duration) {
				t.leaving = true;
				t.slide.setTarget(0);
			}
			float s = t.slide.getF();
			if (t.leaving && s < 0.01f) {
				it.remove();
				continue;
			}
			index++;
		}
		int slot = 0;
		for (int i = TOASTS.size() - 1; i >= 0; i--) {
			Toast t = TOASTS.get(i);
			float s = t.slide.getF();
			int targetY = up ? baseY - (slot + 1) * (h + gap) : baseY + slot * (h + gap);
			if (t.y.getTarget() < 0) t.y.snap(targetY);
			t.y.setTarget(targetY);
			slot++;
			int w = Math.max(130, Math.max(Render2D.width(t.title), Render2D.width(t.message)) + 42);
			int x = (int) (rightX - w * Anim.easeOutCubic(s));
			int y = (int) t.y.get();
			float alpha = Math.min(1f, s * 1.5f);

			Render2D.glow(g, x, y, w, h, 6, ColorUtil.withAlpha(0x000000, (int) (120 * alpha)), 5);
			Render2D.roundedRect(g, x, y, w, h, 6, ColorUtil.withAlpha(0x12121A, (int) (235 * alpha)));
			// Accent strip + icon badge
			Render2D.roundedRect(g, x + 6, y + 6, 18, 18, 5, ColorUtil.fade(ColorUtil.withAlpha(t.type.color, 60), alpha));
			Render2D.centeredText(g, t.type.icon, x + 15, y + 11, ColorUtil.fade(t.type.color, alpha), false);
			Render2D.text(g, t.title, x + 30, y + 6, ColorUtil.fade(0xFFFFFFFF, alpha), false);
			Render2D.text(g, t.message, x + 30, y + 17, ColorUtil.fade(0xFFA1A1B5, alpha), false);
			// Progress bar
			float progress = 1f - Math.min(1f, (System.currentTimeMillis() - t.created) / (float) t.duration);
			int barW = (int) ((w - 12) * progress);
			Render2D.rect(g, x + 6, y + h - 3, w - 12, 1, ColorUtil.fade(0x30FFFFFF, alpha));
			Render2D.rect(g, x + 6, y + h - 3, barW, 1, ColorUtil.fade(t.type.color, alpha));
		}
	}
}
