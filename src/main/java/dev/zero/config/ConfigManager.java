package dev.zero.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.zero.ZeroClient;
import dev.zero.gui.ClickGuiScreen;
import dev.zero.hud.HudLayout;
import dev.zero.module.Module;
import dev.zero.module.ModuleManager;
import dev.zero.setting.Setting;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Saves everything to .minecraft/config/zero/ (local files only).
 * <p>
 * Settings live in <b>profiles</b>: profiles/&lt;name&gt;.json. profiles.json remembers the
 * active profile and which profile each server should load automatically.
 */
public final class ConfigManager {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("zero");
	private static final Path PROFILES = DIR.resolve("profiles");
	private static final Path META = DIR.resolve("profiles.json");
	private static final Path LEGACY = DIR.resolve("config.json");

	private static String active = "Default";
	private static final Map<String, String> serverProfiles = new HashMap<>();
	private static long lastSave;
	private static boolean dirty;

	private ConfigManager() {}

	public static Path dir(String sub) {
		Path p = DIR.resolve(sub);
		try {
			Files.createDirectories(p);
		} catch (IOException ignored) {
		}
		return p;
	}

	private static Path file(String profile) { return PROFILES.resolve(profile + ".json"); }

	// ---- Load / save ---------------------------------------------------------------------------
	public static void load() {
		try {
			Files.createDirectories(PROFILES);
			// Older versions kept a single config.json: it becomes the "Default" profile.
			if (Files.exists(LEGACY) && !Files.exists(file("Default"))) Files.move(LEGACY, file("Default"));
			if (Files.exists(META)) {
				JsonObject meta = JsonParser.parseString(Files.readString(META, StandardCharsets.UTF_8)).getAsJsonObject();
				if (meta.has("active")) active = meta.get("active").getAsString();
				if (meta.has("servers")) for (var e : meta.getAsJsonObject("servers").entrySet()) serverProfiles.put(e.getKey(), e.getValue().getAsString());
			}
		} catch (Exception e) {
			ZeroClient.LOGGER.error("Failed to read profiles", e);
		}
		if (!Files.exists(file(active))) active = "Default";
		apply(active);
	}

	/** Applies a profile file on top of the current state (missing keys keep their value). */
	private static void apply(String profile) {
		Path f = file(profile);
		if (!Files.exists(f)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.has("client")) ClientSettings.fromJson(root.getAsJsonObject("client"));
			if (root.has("gui")) ClickGuiScreen.loadLayout(root.getAsJsonObject("gui"));
			if (root.has("hud")) HudLayout.load(root.getAsJsonObject("hud"));
			if (root.has("blockColors")) dev.zero.util.BlockColors.load(root.getAsJsonObject("blockColors"));
			if (root.has("modules")) {
				JsonObject mods = root.getAsJsonObject("modules");
				for (Module m : ModuleManager.all()) if (mods.has(m.getName())) m.fromJson(mods.getAsJsonObject(m.getName()));
			}
		} catch (Exception e) {
			ZeroClient.LOGGER.error("Failed to load profile {}", profile, e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(PROFILES);
			JsonObject root = new JsonObject();
			root.add("client", ClientSettings.toJson());
			root.add("gui", ClickGuiScreen.saveLayout());
			root.add("hud", HudLayout.save());
			root.add("blockColors", dev.zero.util.BlockColors.save());
			JsonObject mods = new JsonObject();
			for (Module m : ModuleManager.all()) mods.add(m.getName(), m.toJson());
			root.add("modules", mods);
			write(file(active), GSON.toJson(root));
			saveMeta();
			dirty = false;
			lastSave = System.currentTimeMillis();
		} catch (Exception e) {
			ZeroClient.LOGGER.error("Failed to save config", e);
		}
	}

	private static void saveMeta() throws IOException {
		JsonObject meta = new JsonObject();
		meta.addProperty("active", active);
		JsonObject servers = new JsonObject();
		serverProfiles.forEach(servers::addProperty);
		meta.add("servers", servers);
		write(META, GSON.toJson(meta));
	}

	private static void write(Path target, String content) throws IOException {
		Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
		Files.writeString(tmp, content, StandardCharsets.UTF_8);
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	public static void markDirty() { dirty = true; }

	/** Debounced autosave, called every tick. */
	public static void tick() {
		if (dirty && System.currentTimeMillis() - lastSave > 3000) save();
	}

	// ---- Profiles ------------------------------------------------------------------------------
	public static String activeProfile() { return active; }

	public static List<String> profiles() {
		List<String> out = new ArrayList<>();
		try (Stream<Path> s = Files.list(PROFILES)) {
			s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).forEach(n -> out.add(n.substring(0, n.length() - 5)));
		} catch (IOException ignored) {
		}
		if (!out.contains(active)) out.add(active);
		out.sort(String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	/** File-safe profile name, or null if empty. */
	public static String cleanName(String name) {
		String n = name.trim().replaceAll("[^A-Za-z0-9 _.\\-]", "");
		if (n.length() > 24) n = n.substring(0, 24);
		return n.isEmpty() ? null : n;
	}

	public static boolean exists(String name) {
		for (String p : profiles()) if (p.equalsIgnoreCase(name)) return true;
		return false;
	}

	/** Saves the current profile, resets everything and loads another one. */
	public static void switchTo(String name) {
		if (name.equals(active)) return;
		save();
		resetAll();
		active = name;
		apply(name);
		save();
	}

	/** New profile with default settings. */
	public static void createBlank(String name) {
		save();
		resetAll();
		active = name;
		save();
	}

	/** New profile that starts as a copy of the current one. */
	public static void duplicate(String name) {
		save();
		active = name;
		save();
	}

	public static void rename(String newName) {
		String old = active;
		save();
		try {
			Files.move(file(old), file(newName), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			ZeroClient.LOGGER.warn("Rename failed", e);
			return;
		}
		active = newName;
		serverProfiles.replaceAll((server, p) -> p.equals(old) ? newName : p);
		save();
	}

	public static void delete(String name) {
		if (name.equals(active)) return;
		try {
			Files.deleteIfExists(file(name));
		} catch (IOException e) {
			ZeroClient.LOGGER.warn("Delete failed", e);
		}
		serverProfiles.values().removeIf(p -> p.equals(name));
		save();
	}

	/** Profile that should load automatically on this server, or null. */
	public static String profileForServer(String serverId) { return serverProfiles.get(serverId.toLowerCase(Locale.ROOT)); }

	public static void setServerProfile(String serverId, String profile) {
		if (profile == null) serverProfiles.remove(serverId.toLowerCase(Locale.ROOT));
		else serverProfiles.put(serverId.toLowerCase(Locale.ROOT), profile);
		save();
	}

	/** Every module off and every setting back to its default. */
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void resetAll() {
		for (Module m : ModuleManager.all()) {
			m.setEnabledSilently(false);
			m.keybind.reset();
			for (Setting s : m.getSettings()) s.reset();
		}
		for (Setting s : ClientSettings.SETTINGS) s.reset();
		ClickGuiScreen.clearLayout();
		HudLayout.clear();
		dev.zero.util.BlockColors.clearCustom();
	}
}
