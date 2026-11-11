package dev.zero.modules;

import dev.zero.config.ClientSettings;
import dev.zero.config.FindsStore;
import dev.zero.hud.Notifications;
import dev.zero.module.Category;
import dev.zero.module.Module;
import dev.zero.render.Render2D;
import dev.zero.render.Render3D;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.ColorSetting;
import dev.zero.setting.EnumSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.setting.StringListSetting;
import dev.zero.util.ChatUtil;
import dev.zero.util.ColorUtil;
import dev.zero.util.Sounds;
import dev.zero.util.WorldUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Detects players entering / leaving your visual range, tracks logout spots and
 * (optionally) tab-list joins and leaves. Uses only entity and tab-list data the server
 * already broadcasts to every client.
 */
public class PlayerDetector extends Module {
	public enum JoinAlerts { OFF, FRIENDS, ALL }

	private final BoolSetting toast = add(new BoolSetting("Toast", "Toast when a player enters / leaves range", true));
	private final BoolSetting chat = add(new BoolSetting("Chat Message", "Local chat message with coordinates", true));
	private final BoolSetting sound = add(new BoolSetting("Sound", "Local alert sound", true));
	private final BoolSetting leaveAlerts = add(new BoolSetting("Leave Alerts", "Also alert when a player leaves range", true));
	private final NumberSetting cooldown = add(new NumberSetting("Alert Cooldown", "Seconds before the same player alerts again", 30, 0, 300, 5, "s"));
	public final StringListSetting friends = add(new StringListSetting("Friends", "Friends are colored and never trigger alerts", List.of()));
	private final BoolSetting box = add(new BoolSetting("Box", "Box around players", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Lines to players", true));
	private final BoolSetting nametags = add(new BoolSetting("Nametags", "Name, health and distance through walls", true));
	private final ColorSetting color = add(new ColorSetting("Color", "Player color", 0xFFFF4D6D, false));
	private final ColorSetting friendColor = add(new ColorSetting("Friend Color", "Friend color", 0xFF4ADE80, false));
	private final BoolSetting radar = add(new BoolSetting("Radar HUD", "List of nearby players on the HUD", true));
	private final BoolSetting logoutSpots = add(new BoolSetting("Logout Spots", "Mark where players logged out", true));
	private final EnumSetting<JoinAlerts> joinAlerts = add(new EnumSetting<>("Join/Leave", "Tab-list join and leave alerts", JoinAlerts.FRIENDS));
	private final BoolSetting log = add(new BoolSetting("Log To File", "Log encounters to config/zero/finds", true));

	private static final class Tracked {
		final String name;
		Vec3 lastPos;
		long lastSeen;
		Tracked(String name, Vec3 pos) {
			this.name = name;
			this.lastPos = pos;
		}
	}

	private record LogoutSpot(UUID id, String name, AABB box, long time, String dim) {}
	private record Pending(Tracked tracked, UUID id, long time, String dim) {}

	private final Map<UUID, Tracked> inRange = new HashMap<>();
	private final Map<UUID, Long> lastAlert = new HashMap<>();
	private final List<Pending> pendingLeaves = new ArrayList<>();
	private final List<LogoutSpot> logouts = new ArrayList<>();
	private Set<UUID> lastTab = null;
	private int tick;

	public PlayerDetector() {
		super("PlayerDetector", "Alerts when players enter your render distance", Category.MISC);
	}

	@Override
	public String getInfo() { return inRange.isEmpty() ? null : String.valueOf(inRange.size()); }

	@Override
	protected void onEnable() {
		inRange.clear();
		lastTab = null;
		// Players already in range when enabling are tracked silently.
		if (mc.level != null && mc.player != null) {
			for (AbstractClientPlayer p : others()) inRange.put(p.getUUID(), new Tracked(name(p), p.position()));
		}
	}

	@Override
	public void onWorldLeave() {
		inRange.clear();
		pendingLeaves.clear();
		lastTab = null;
	}

	public boolean isFriend(String name) { return friends.containsIgnoreCase(name); }

	private List<AbstractClientPlayer> others() {
		List<AbstractClientPlayer> list = new ArrayList<>();
		for (AbstractClientPlayer p : mc.level.players()) if (p != mc.player) list.add(p);
		return list;
	}

	private static String name(AbstractClientPlayer p) { return p.getGameProfile().name(); }

	@Override
	public void onTick() {
		tick++;
		long now = System.currentTimeMillis();
		String dim = WorldUtil.dimensionName(mc.level);
		Set<UUID> present = new HashSet<>();
		for (AbstractClientPlayer p : others()) {
			UUID id = p.getUUID();
			present.add(id);
			Tracked t = inRange.get(id);
			if (t == null) {
				t = new Tracked(name(p), p.position());
				inRange.put(id, t);
				pendingLeaves.removeIf(pl -> pl.id.equals(id));
				logouts.removeIf(l -> l.id.equals(id));
				onEnter(p, t, now);
			}
			t.lastPos = p.position();
			t.lastSeen = now;
		}
		for (Iterator<Map.Entry<UUID, Tracked>> it = inRange.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, Tracked> e = it.next();
			if (present.contains(e.getKey())) continue;
			it.remove();
			pendingLeaves.add(new Pending(e.getValue(), e.getKey(), now, dim));
		}
		// Decide a few ticks later whether they logged out or just walked away.
		var connection = mc.getConnection();
		for (Iterator<Pending> it = pendingLeaves.iterator(); it.hasNext(); ) {
			Pending p = it.next();
			if (now - p.time < 400) continue;
			it.remove();
			boolean online = connection != null && connection.getPlayerInfo(p.id) != null;
			if (!online && logoutSpots.get()) {
				Vec3 pos = p.tracked.lastPos;
				logouts.add(new LogoutSpot(p.id, p.tracked.name, new AABB(pos.x - 0.3, pos.y, pos.z - 0.3, pos.x + 0.3, pos.y + 1.8, pos.z + 0.3), now, p.dim));
				while (logouts.size() > 50) logouts.remove(0);
				Notifications.push(p.tracked.name + " logged out", fmt(pos), Notifications.Type.WARNING, 4500);
				if (log.get()) FindsStore.appendLog("Logout | " + p.tracked.name + " | " + p.dim + " | " + fmt(pos));
			} else if (online) {
				onLeave(p.tracked, now);
			}
		}
		if (tick % 10 == 0) checkTabList();
	}

	private boolean canAlert(UUID id, long now) {
		Long last = lastAlert.get(id);
		if (last != null && now - last < cooldown.get() * 1000) return false;
		lastAlert.put(id, now);
		return true;
	}

	private void onEnter(AbstractClientPlayer p, Tracked t, long now) {
		boolean friend = isFriend(t.name);
		String where = fmt(p.position()) + "  " + WorldUtil.distance(mc.player.distanceTo(p));
		if (log.get()) FindsStore.appendLog("Player entered range | " + t.name + " | " + WorldUtil.dimensionName(mc.level) + " | " + fmt(p.position()));
		if (friend || !canAlert(p.getUUID(), now)) return;
		if (toast.get()) Notifications.push(t.name + " entered range", where, Notifications.Type.ALERT, 5000);
		if (chat.get()) ChatUtil.info(Component.literal(t.name).withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
			.append(Component.literal(" entered visual range at " + where).withStyle(ChatFormatting.GRAY)));
		if (sound.get()) Sounds.alert();
	}

	private void onLeave(Tracked t, long now) {
		if (!leaveAlerts.get() || isFriend(t.name)) return;
		if (toast.get()) Notifications.push(t.name + " left range", fmt(t.lastPos), Notifications.Type.INFO, 3000);
	}

	private void checkTabList() {
		var connection = mc.getConnection();
		if (connection == null) return;
		Map<UUID, String> now = new HashMap<>();
		for (PlayerInfo info : connection.getOnlinePlayers()) now.put(info.getProfile().id(), info.getProfile().name());
		if (lastTab != null && joinAlerts.get() != JoinAlerts.OFF) {
			for (Map.Entry<UUID, String> e : now.entrySet()) {
				if (!lastTab.contains(e.getKey()) && (joinAlerts.get() == JoinAlerts.ALL || isFriend(e.getValue())))
					Notifications.push(e.getValue() + " joined", "Server", Notifications.Type.INFO, 3500);
			}
			if (lastNames != null) {
				for (Map.Entry<UUID, String> e : lastNames.entrySet()) {
					if (!now.containsKey(e.getKey()) && (joinAlerts.get() == JoinAlerts.ALL || isFriend(e.getValue())))
						Notifications.push(e.getValue() + " left", "Server", Notifications.Type.INFO, 3500);
				}
			}
		}
		lastTab = new HashSet<>(now.keySet());
		lastNames = now;
	}

	private Map<UUID, String> lastNames;

	private static String fmt(Vec3 p) { return String.format(Locale.ROOT, "%.0f, %.0f, %.0f", p.x, p.y, p.z); }

	// ---- Rendering ------------------------------------------------------------------------
	@Override
	public void onRender3D(float pt) {
		Vec3 cam = WorldUtil.cameraPos();
		for (AbstractClientPlayer p : others()) {
			boolean friend = isFriend(name(p));
			int c = friend ? friendColor.get() : color.get();
			double dx = Mth.lerp(pt, p.xo, p.getX()) - p.getX();
			double dy = Mth.lerp(pt, p.yo, p.getY()) - p.getY();
			double dz = Mth.lerp(pt, p.zo, p.getZ()) - p.getZ();
			AABB bb = p.getBoundingBox().move(dx, dy, dz);
			if (box.get()) Render3D.box(bb, ColorUtil.withAlpha(c, 40), ColorUtil.withAlpha(c, 230), 2f, true);
			Vec3 center = bb.getCenter();
			if (tracers.get() && !friend) Render3D.tracer(center, ColorUtil.withAlpha(c, 220), 2f);
			if (nametags.get()) {
				int hp = Math.round(p.getHealth() + p.getAbsorptionAmount());
				String hpColor = hp > 14 ? "§a" : hp > 7 ? "§e" : "§c";
				String text = name(p) + " " + hpColor + hp + "❤ §7" + WorldUtil.distance(cam.distanceTo(center));
				Render3D.label(new Vec3(center.x, bb.maxY + 0.45, center.z), text, c, 1.1f);
			}
		}
		if (logoutSpots.get()) {
			String dim = WorldUtil.dimensionName(mc.level);
			for (LogoutSpot l : logouts) {
				if (!l.dim.equals(dim)) continue;
				Render3D.box(l.box, 0x309CA3AF, 0xC09CA3AF, 1.5f, true);
				long mins = (System.currentTimeMillis() - l.time) / 60000;
				Render3D.label(new Vec3(l.box.getCenter().x, l.box.maxY + 0.4, l.box.getCenter().z), l.name + " §7logout " + mins + "m ago", 0xFFCBD5E1, 1f);
			}
		}
	}

	@Override
	public void onRender2D(GuiGraphics g, float partialTick) {
		if (!radar.get()) return;
		List<AbstractClientPlayer> list = others();
		if (list.isEmpty()) return;
		list.sort((a, b) -> Double.compare(mc.player.distanceToSqr(a), mc.player.distanceToSqr(b)));
		int rows = Math.min(8, list.size());
		int rowH = 18, w = 170;
		int h = 16 + rows * rowH;
		int[] pos = dev.zero.hud.Hud.slot("Player radar", w, h);
		int x = pos[0], y = pos[1];
		int accent = color.get();
		Render2D.glow(g, x, y, w, h, 5, ColorUtil.withAlpha(accent, 60), 4);
		Render2D.roundedRect(g, x, y, w, h, 5, 0xE0101016);
		Render2D.gradientH(g, x + 5, y, w - 10, 1, accent, ClientSettings.accent());
		Render2D.text(g, "Players in range", x + 6, y + 4, 0xFFFFFFFF, false);
		String count = String.valueOf(list.size());
		Render2D.text(g, count, x + w - 6 - Render2D.width(count), y + 4, accent, false);
		Vec3 cam = mc.player.position();
		for (int i = 0; i < rows; i++) {
			AbstractClientPlayer p = list.get(i);
			int ry = y + 15 + i * rowH;
			boolean friend = isFriend(name(p));
			ItemStack held = p.getMainHandItem();
			if (!held.isEmpty()) g.renderItem(held, x + 5, ry);
			int nameColor = friend ? friendColor.get() : 0xFFF2F2F7;
			Render2D.text(g, name(p), x + 24, ry + 1, nameColor, false);
			float hp = p.getHealth() + p.getAbsorptionAmount();
			float frac = Mth.clamp(hp / p.getMaxHealth(), 0, 1);
			int barColor = ColorUtil.lerp(0xFFFF4D6D, 0xFF4ADE80, frac);
			Render2D.rect(g, x + 24, ry + 12, 60, 2, 0x40FFFFFF);
			Render2D.rect(g, x + 24, ry + 12, (int) (60 * frac), 2, barColor);
			String d = WorldUtil.distance(cam.distanceTo(p.position())) + " " + WorldUtil.direction(cam, p.getX(), p.getZ());
			Render2D.text(g, d, x + w - 6 - Render2D.width(d), ry + 5, 0xFF9A9AB0, false);
		}
	}
}
