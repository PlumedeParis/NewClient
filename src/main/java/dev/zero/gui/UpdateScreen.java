package dev.zero.gui;

import dev.zero.render.Render2D;
import dev.zero.update.Updater;
import dev.zero.util.ColorUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** "A new ZERØ is available": what's new, and update now / next launch / later / skip. */
public class UpdateScreen extends Screen {
	private record Button(String label, String tip, int color, Runnable action) {}

	private final Screen parent;
	private MarkdownText notes;
	private String notesFor;
	private double scroll;
	private final List<int[]> rects = new ArrayList<>();
	private List<Button> buttons = List.of();

	public UpdateScreen(Screen parent) {
		super(Component.literal("ZERØ update"));
		this.parent = parent;
	}

	private int[] window() {
		int w = Math.min(width - 20, 380), h = Math.min(height - 20, 270);
		return new int[] {width / 2 - w / 2, height / 2 - h / 2, w, h};
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		g.fillGradient(0, 0, width, height, 0xC005050A, 0xE00B0B18);
	}

	private List<Button> buttonsFor(Updater.State s) {
		return switch (s) {
			case AVAILABLE -> List.of(
				new Button("Update now", "Closes Minecraft, installs the update and opens Minecraft again", Theme.accent(), () -> Updater.download(true)),
				new Button("Next launch", "Downloads now, installs when you quit Minecraft", 0xFF3A3A4E, () -> Updater.download(false)),
				new Button("Later", "Ask me again next time", 0xFF2A2A38, this::onClose),
				new Button("Skip", "Don't ask again for this version", 0xFF2A2A38, () -> {
					Updater.skip(Updater.info().version());
					onClose();
				}));
			case DOWNLOADING -> List.of(new Button("Hide", "The download continues in the background", 0xFF2A2A38, this::onClose));
			case READY -> List.of(
				new Button("Restart now", "Closes Minecraft, installs the update and opens Minecraft again", Theme.accent(), () -> Updater.download(true)),
				new Button("OK", "It installs when you quit Minecraft", 0xFF2A2A38, this::onClose));
			case FAILED -> List.of(new Button("Try again", "", Theme.accent(), Updater::check), new Button("Close", "", 0xFF2A2A38, this::onClose));
			case UP_TO_DATE -> List.of(new Button("OK", "", Theme.accent(), this::onClose));
			default -> List.of(new Button("Close", "", 0xFF2A2A38, this::onClose));
		};
	}

	@Override
	public void render(GuiGraphics g, int mx, int my, float partialTick) {
		super.render(g, mx, my, partialTick);
		int[] win = window();
		int x = win[0], y = win[1], w = win[2], h = win[3];
		int accent = Theme.accent();
		Updater.State st = Updater.state();
		Updater.Info info = Updater.info();
		Render2D.glow(g, x, y, w, h, 9, ColorUtil.withAlpha(accent, 80), 10);
		Render2D.roundedRect(g, x, y, w, h, 9, Theme.panelBg());
		Render2D.gradientH(g, x + 9, y, w - 18, 1, accent, Theme.accent2());

		// header: NEW badge + title
		Render2D.roundedRect(g, x + 10, y + 10, 30, 13, 4, accent);
		Render2D.text(g, "§lNEW", x + 13, y + 13, 0xFFFFFFFF, false);
		String title = switch (st) {
			case CHECKING -> "Looking for updates…";
			case UP_TO_DATE -> "ZERØ is up to date";
			case FAILED -> "Could not check for updates";
			default -> info == null ? "ZERØ update" : "ZERØ " + info.version() + " is available";
		};
		Render2D.text(g, "§l" + title, x + 46, y + 13, Theme.TEXT, false);
		Render2D.text(g, "You have " + Updater.current(), x + 46, y + 25, Theme.SUBTEXT, false);

		// body
		boolean bar = st == Updater.State.DOWNLOADING || st == Updater.State.READY;
		int top = y + 42, bottom = y + h - (bar ? 66 : 40);
		if (st == Updater.State.FAILED) {
			Render2D.text(g, Render2D.trim(Updater.error(), w - 24), x + 12, top + 4, 0xFFFF8A8A, false);
			Render2D.text(g, "Your game is not affected. You can turn checks off in Client → General.", x + 12, top + 18, Theme.SUBTEXT, false);
		} else if (info != null && st != Updater.State.UP_TO_DATE) {
			if (notes == null || !info.version().equals(notesFor)) {
				notes = new MarkdownText(info.changelog().isEmpty() ? "- Improvements and fixes" : "## " + info.version() + "\n" + info.changelog());
				notesFor = info.version();
			}
			int ch = notes.height(w - 28);
			scroll = Math.max(0, Math.min(scroll, Math.max(0, ch - (bottom - top))));
			Render2D.roundedRect(g, x + 8, top - 4, w - 16, bottom - top + 8, 6, 0x50000000);
			g.enableScissor(x + 8, top, x + w - 8, bottom);
			notes.draw(g, x + 14, top - (int) scroll, w - 28);
			g.disableScissor();
		} else if (st == Updater.State.UP_TO_DATE) {
			Render2D.text(g, "You have the latest version. Enjoy!", x + 12, top + 4, Theme.SUBTEXT, false);
		}

		// progress
		if (st == Updater.State.DOWNLOADING || st == Updater.State.READY) {
			float p = st == Updater.State.READY ? 1 : Updater.progress();
			int by = y + h - 46;
			Render2D.roundedRect(g, x + 12, by, w - 24, 5, 2, 0xFF23232E);
			if (p > 0) Render2D.roundedRect(g, x + 12, by, Math.max(5, (int) ((w - 24) * p)), 5, 2, accent);
			String s = st == Updater.State.READY ? "✔ Downloaded and verified — installs when you quit Minecraft" : "Downloading… " + Math.round(p * 100) + "%";
			Render2D.text(g, s, x + 12, by - 11, st == Updater.State.READY ? 0xFF4ADE80 : Theme.SUBTEXT, false);
		}

		// buttons
		buttons = buttonsFor(st);
		rects.clear();
		int bw = (w - 20 - (buttons.size() - 1) * 6) / Math.max(1, buttons.size());
		String tip = null;
		for (int i = 0; i < buttons.size(); i++) {
			Button b = buttons.get(i);
			int bx = x + 10 + i * (bw + 6), by = y + h - 28;
			boolean hover = Render2D.hovered(mx, my, bx, by, bw, 18);
			Render2D.roundedRect(g, bx, by, bw, 18, 5, hover ? ColorUtil.brighten(b.color(), 0.15f) : b.color());
			Render2D.centeredText(g, b.label(), bx + bw / 2, by + 5, 0xFFFFFFFF, false);
			rects.add(new int[] {bx, by, bw, 18});
			if (hover && !b.tip().isEmpty()) tip = b.tip();
		}
		if (tip != null) Render2D.centeredText(g, tip, width / 2, y + h + 6, Theme.SUBTEXT, false);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		for (int i = 0; i < rects.size() && i < buttons.size(); i++) {
			int[] r = rects.get(i);
			if (Render2D.hovered(event.x(), event.y(), r[0], r[1], r[2], r[3])) {
				dev.zero.util.Sounds.click();
				buttons.get(i).action().run();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
		scroll -= vertical * 20;
		return true;
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
