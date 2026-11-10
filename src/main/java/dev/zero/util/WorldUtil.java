package dev.zero.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

public final class WorldUtil {
	private WorldUtil() {}

	/** A filesystem-safe id of the current server (or singleplayer world). */
	public static String serverId() {
		Minecraft mc = Minecraft.getInstance();
		ServerData data = mc.getCurrentServer();
		String raw;
		if (data != null) raw = data.ip;
		else if (mc.getSingleplayerServer() != null) raw = "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName();
		else raw = "unknown";
		return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
	}

	public static String dimensionName(Level level) {
		if (level == null) return "?";
		return level.dimension().identifier().getPath();
	}

	public static String shortDim(Level level) {
		String d = dimensionName(level);
		return switch (d) {
			case "overworld" -> "OW";
			case "the_nether" -> "Nether";
			case "the_end" -> "End";
			default -> d;
		};
	}

	public static Vec3 cameraPos() {
		return Minecraft.getInstance().gameRenderer.getMainCamera().position();
	}

	public static String fmt(BlockPos p) { return p.getX() + ", " + p.getY() + ", " + p.getZ(); }

	public static String distance(double d) {
		if (d >= 1000) return String.format(Locale.ROOT, "%.1fk", d / 1000);
		return String.valueOf((int) d) + "m";
	}

	/** 8-wind compass direction from the camera to a point. */
	public static String direction(Vec3 from, double x, double z) {
		double angle = Math.toDegrees(Math.atan2(-(x - from.x), z - from.z));
		angle = (angle + 360) % 360;
		String[] dirs = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
		return dirs[(int) Math.round(angle / 45) % 8];
	}
}
