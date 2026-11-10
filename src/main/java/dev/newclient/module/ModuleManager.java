package dev.newclient.module;

import dev.newclient.modules.BaseFinder;
import dev.newclient.modules.BlockESP;
import dev.newclient.modules.Freecam;
import dev.newclient.modules.PlayerDetector;
import dev.newclient.modules.SusChunk;

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

	private ModuleManager() {}

	public static void init() {
		MODULES.add(blockEsp = new BlockESP());
		MODULES.add(freecam = new Freecam());
		MODULES.add(baseFinder = new BaseFinder());
		MODULES.add(susChunk = new SusChunk());
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
