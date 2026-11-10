package dev.newclient.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.newclient.NewClient;
import dev.newclient.util.WorldUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persistent list of everything the client found (bases, stashes, sus chunks, players),
 * saved per server in config/newclient/finds/. Local files only.
 */
public final class FindsStore {
	public enum Kind {
		STASH("Stash", 0xFFFFB347),
		BASE("Base", 0xFFFF5C7A),
		POSSIBLE("Possible Base", 0xFFFFE066),
		SUS("Sus Chunk", 0xFFFF7AF5),
		PLAYER("Player", 0xFF60A5FA),
		LOGOUT("Logout Spot", 0xFF9CA3AF);

		public final String title;
		public final int color;

		Kind(String title, int color) {
			this.title = title;
			this.color = color;
		}
	}

	public static final class Find {
		public final Kind kind;
		public final int x, y, z;
		public final String dimension;
		public final String details;
		public final long time;

		public Find(Kind kind, int x, int y, int z, String dimension, String details, long time) {
			this.kind = kind;
			this.x = x;
			this.y = y;
			this.z = z;
			this.dimension = dimension;
			this.details = details;
			this.time = time;
		}
	}

	private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final List<Find> FINDS = new ArrayList<>();
	private static String loadedServer;

	private FindsStore() {}

	private static Path dir() { return ConfigManager.dir("finds"); }

	/** Makes sure the finds of the current server are loaded. */
	public static void ensureLoaded() {
		String server = WorldUtil.serverId();
		if (server.equals(loadedServer)) return;
		loadedServer = server;
		FINDS.clear();
		Path file = dir().resolve(server + ".json");
		if (!Files.exists(file)) return;
		try {
			JsonArray arr = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
			for (var e : arr) {
				JsonObject o = e.getAsJsonObject();
				FINDS.add(new Find(Kind.valueOf(o.get("kind").getAsString()), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
					o.get("dim").getAsString(), o.get("details").getAsString(), o.get("time").getAsLong()));
			}
		} catch (Exception ex) {
			NewClient.LOGGER.warn("Could not read finds for {}", server, ex);
		}
	}

	public static List<Find> all() {
		ensureLoaded();
		return Collections.unmodifiableList(FINDS);
	}

	/** Returns an existing find of the same kind group within {@code radius} blocks, or null. */
	public static Find near(String dim, int x, int z, int radius, Kind... kinds) {
		ensureLoaded();
		for (Find f : FINDS) {
			if (!f.dimension.equals(dim)) continue;
			boolean kindOk = kinds.length == 0;
			for (Kind k : kinds) if (f.kind == k) kindOk = true;
			if (!kindOk) continue;
			long dx = f.x - x, dz = f.z - z;
			if (dx * dx + dz * dz <= (long) radius * radius) return f;
		}
		return null;
	}

	public static void add(Find find, boolean writeLog) {
		ensureLoaded();
		FINDS.add(find);
		save();
		if (writeLog) appendLog(find.kind.title + " | " + find.dimension + " | " + find.x + " " + find.y + " " + find.z + " | " + find.details);
	}

	public static void remove(Find find) {
		FINDS.remove(find);
		save();
	}

	public static void clear(Kind kind) {
		ensureLoaded();
		FINDS.removeIf(f -> kind == null || f.kind == kind);
		save();
	}

	public static void appendLog(String line) {
		String server = WorldUtil.serverId();
		Path file = dir().resolve(server + ".log");
		try {
			Files.writeString(file, "[" + LocalDateTime.now().format(LOG_TIME) + "] " + line + System.lineSeparator(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			NewClient.LOGGER.warn("Could not write log", e);
		}
	}

	private static void save() {
		if (loadedServer == null) return;
		JsonArray arr = new JsonArray();
		for (Find f : FINDS) {
			JsonObject o = new JsonObject();
			o.addProperty("kind", f.kind.name());
			o.addProperty("x", f.x);
			o.addProperty("y", f.y);
			o.addProperty("z", f.z);
			o.addProperty("dim", f.dimension);
			o.addProperty("details", f.details);
			o.addProperty("time", f.time);
			arr.add(o);
		}
		try {
			Files.writeString(dir().resolve(loadedServer + ".json"), ConfigManager.GSON.toJson(arr), StandardCharsets.UTF_8);
		} catch (IOException e) {
			NewClient.LOGGER.warn("Could not save finds", e);
		}
	}
}
