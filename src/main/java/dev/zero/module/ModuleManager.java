package dev.zero.module;

import dev.zero.modules.AmethystFinder;
import dev.zero.modules.BaseFinder;
import dev.zero.modules.BaseRadar;
import dev.zero.modules.GrowthFinder;
import dev.zero.modules.RotatedDeepslate;
import dev.zero.modules.SpawnerFinder;
import dev.zero.modules.TunnelFinder;
import dev.zero.modules.BlockESP;
import dev.zero.modules.Crosshair;
import dev.zero.modules.SpawnerESP;
import dev.zero.modules.Freecam;
import dev.zero.modules.PlayerDetector;
import dev.zero.modules.SeedOreFinder;
import dev.zero.modules.StorageESP;
import dev.zero.modules.XRay;
import dev.zero.modules.SusChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleManager {
	private static final List<Module> MODULES = new ArrayList<>();

	public static BlockESP blockEsp;
	public static BaseFinder baseFinder;
	public static Freecam freecam;
	public static PlayerDetector playerDetector;
	public static SusChunk susChunk;
	public static StorageESP storageEsp;
	public static XRay xray;
	public static SpawnerESP spawnerEsp;
	public static Crosshair crosshair;
	public static SeedOreFinder seedOreFinder;
	public static AmethystFinder amethystFinder;
	public static GrowthFinder growthFinder;
	public static RotatedDeepslate rotatedDeepslate;
	public static SpawnerFinder spawnerFinder;
	public static TunnelFinder tunnelFinder;
	public static BaseRadar baseRadar;
	public static dev.zero.modules.SafeMiner safeMiner;
	public static dev.zero.modules.Fullbright fullbright;
	public static dev.zero.modules.DonutHunter donutHunter;

	private ModuleManager() {}

	public static void init() {
		MODULES.add(blockEsp = new BlockESP());
		MODULES.add(storageEsp = new StorageESP());
		MODULES.add(xray = new XRay());
		MODULES.add(fullbright = new dev.zero.modules.Fullbright());
		MODULES.add(spawnerEsp = new SpawnerESP());
		MODULES.add(crosshair = new Crosshair());
		MODULES.add(freecam = new Freecam());
		MODULES.add(baseFinder = new BaseFinder());
		MODULES.add(susChunk = new SusChunk());
		MODULES.add(seedOreFinder = new SeedOreFinder());
		MODULES.add(safeMiner = new dev.zero.modules.SafeMiner());
		MODULES.add(amethystFinder = new AmethystFinder());
		MODULES.add(growthFinder = new GrowthFinder());
		MODULES.add(rotatedDeepslate = new RotatedDeepslate());
		MODULES.add(spawnerFinder = new SpawnerFinder());
		MODULES.add(tunnelFinder = new TunnelFinder());
		MODULES.add(donutHunter = new dev.zero.modules.DonutHunter());
		MODULES.add(baseRadar = new BaseRadar());
		MODULES.add(playerDetector = new PlayerDetector());
	}

	public static List<Module> all() { return Collections.unmodifiableList(MODULES); }

	public static List<Module> byCategory(Category category) {
		List<Module> list = new ArrayList<>();
		for (Module m : MODULES) if (m.getCategory() == category) list.add(m);
		return list;
	}

	public static Module byName(String name) {
		for (Module m : MODULES) if (m.getName().equalsIgnoreCase(name)) return m;
		return null;
	}
}
