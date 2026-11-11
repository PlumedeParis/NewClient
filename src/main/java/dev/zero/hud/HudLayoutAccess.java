package dev.zero.hud;

import java.util.Map;

/** Read access to the element rectangles drawn last frame (used by the HUD editor). */
public final class HudLayoutAccess {
	private HudLayoutAccess() {}

	public static Map<String, int[]> last() { return HudLayout.LAST; }
}
