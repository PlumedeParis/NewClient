package dev.newclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.newclient.NewClient;
import dev.newclient.gui.ClickGuiScreen;
import dev.newclient.module.Module;
import dev.newclient.module.ModuleManager;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Saves everything to .minecraft/config/newclient/. Local files only. */
public final class ConfigManager {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("newclient");
	private static final Path FILE = DIR.resolve("config.json");

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

	public static void load() {
		if (!Files.exists(FILE)) return;
		try {
			JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.has("client")) ClientSettings.fromJson(root.getAsJsonObject("client"));
			if (root.has("gui")) ClickGuiScreen.loadLayout(root.getAsJsonObject("gui"));
			if (root.has("modules")) {
				JsonObject mods = root.getAsJsonObject("modules");
				for (Module m : ModuleManager.all()) {
					if (mods.has(m.getName())) m.fromJson(mods.getAsJsonObject(m.getName()));
				}
			}
		} catch (Exception e) {
			NewClient.LOGGER.error("Failed to load config", e);
		}
	}

	public static void save() {
		try {
			Files.createDirectories(DIR);
			JsonObject root = new JsonObject();
			root.add("client", ClientSettings.toJson());
			root.add("gui", ClickGuiScreen.saveLayout());
			JsonObject mods = new JsonObject();
			for (Module m : ModuleManager.all()) mods.add(m.getName(), m.toJson());
			root.add("modules", mods);
			Path tmp = FILE.resolveSibling("config.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
			lastSave = System.currentTimeMillis();
		} catch (Exception e) {
			NewClient.LOGGER.error("Failed to save config", e);
		}
	}

	public static void markDirty() { dirty = true; }

	/** Debounced autosave, called every tick. */
	public static void tick() {
		if (dirty && System.currentTimeMillis() - lastSave > 3000) save();
	}
}
