package dev.zero.module;

public enum Category {
	RENDER("Render", 0xFF8B7CFF, "◉"),
	WORLD("World", 0xFF4FD1C5, "⬢"),
	HUNT("Hunt", 0xFFFFB347, "\u2316"),
	MISC("Misc", 0xFFFF7AA2, "✦");

	public final String title;
	public final int color;
	public final String icon;

	Category(String title, int color, String icon) {
		this.title = title;
		this.color = color;
		this.icon = icon;
	}
}
