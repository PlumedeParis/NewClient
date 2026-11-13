package dev.zero;

import com.mojang.blaze3d.platform.InputConstants;
import dev.zero.config.ClientSettings;
import dev.zero.config.ConfigManager;
import dev.zero.gui.ClickGuiScreen;
import dev.zero.hud.Hud;
import dev.zero.module.Module;
import dev.zero.module.ModuleManager;
import dev.zero.render.Render3D;
import dev.zero.util.Perf;
import dev.zero.scan.ChunkScanner;
import net.minecraft.client.renderer.MultiBufferSource;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * ZERØ - a strictly client-side utility client.
 * <p>
 * Nothing in this mod sends packets, registers network channels or interacts with the server.
 * It only reads what the server already sent (chunks, entities, tab list) and renders locally.
 */
public final class ZeroClient implements ClientModInitializer {
	public static final String MOD_ID = "zero";
	public static final Logger LOGGER = LoggerFactory.getLogger("ZERØ");

	private static final Set<Integer> HELD_KEYS = new HashSet<>();
	private static ClientLevel lastLevel;

	@Override
	public void onInitializeClient() {
		ModuleManager.init();
		ConfigManager.load();

		ClientTickEvents.END_CLIENT_TICK.register(ZeroClient::tick);
		ClientChunkEvents.CHUNK_LOAD.register(ChunkScanner::onChunkLoad);
		ClientChunkEvents.CHUNK_UNLOAD.register(ChunkScanner::onChunkUnload);
		ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, world) -> {
			ChunkScanner.onWorldChange();
			lastLevel = world;
			// Auto-load the profile chosen for this server, once the join has finished (next tick).
			if (world != null) profileCheckTicks = 20;
		});
		// joining a world / server (not a dimension change): the intro plays again on the next GUI open
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> dev.zero.gui.IntroScreen.onJoin());
		WorldRenderEvents.END_MAIN.register(context -> render3D((MultiBufferSource.BufferSource) context.consumers()));
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), (g, d) -> {
			long t = System.nanoTime();
			Hud.render(g, d);
			Perf.module("_hud", System.nanoTime() - t);
		});
		// Custom crosshair: draws ZERØ's design instead of the vanilla one while enabled.
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (graphics, delta) -> {
			if (!hidden && ModuleManager.crosshair != null && ModuleManager.crosshair.isEnabled()) ModuleManager.crosshair.renderCrosshair(graphics);
			else vanilla.render(graphics, delta);
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ConfigManager.save());


		LOGGER.info("ZERØ loaded with {} modules (client-side only)", ModuleManager.all().size());
	}

	private static int profileCheckTicks;

	/** "Hide All": nothing is drawn (ESP, HUD, pop-ups) while modules keep working. */
	private static boolean hidden;

	public static boolean hidden() { return hidden; }

	private static void tick(Minecraft mc) {
		if (profileCheckTicks > 0 && --profileCheckTicks == 0 && mc.level != null) {
			String wanted = ConfigManager.profileForServer(dev.zero.util.WorldUtil.serverId());
			if (wanted != null && !wanted.equals(ConfigManager.activeProfile()) && ConfigManager.exists(wanted)) {
				ConfigManager.switchTo(wanted);
				dev.zero.hud.Notifications.push("Profile loaded", wanted + " (auto for this server)", dev.zero.hud.Notifications.Type.INFO, 3500);
			}
		}
		if (mc.level == null && lastLevel != null) {
			ChunkScanner.onWorldChange();
			lastLevel = null;
		}
		handleKeys(mc);
		if (mc.level != null && mc.player != null) {
			long t0 = System.nanoTime();
			ChunkScanner.tick();
			Perf.scan(System.nanoTime() - t0);
			for (Module m : ModuleManager.all()) {
				if (m.isEnabled()) {
					try {
						m.onTick();
					} catch (Exception e) {
						LOGGER.error("Error ticking {}", m.getName(), e);
					}
				}
			}
		}
		ConfigManager.tick();
	}

	private static void handleKeys(Minecraft mc) {
		Set<Integer> wanted = new HashSet<>();
		wanted.add(ClientSettings.GUI_KEY.get());
		wanted.add(ClientSettings.HIDE_KEY.get());
		for (Module m : ModuleManager.all()) if (m.keybind.isBound()) wanted.add(m.keybind.get());
		HELD_KEYS.retainAll(wanted);

		for (int key : wanted) {
			if (key == GLFW.GLFW_KEY_UNKNOWN) continue;
			boolean down = InputConstants.isKeyDown(mc.getWindow(), key);
			if (!down) {
				HELD_KEYS.remove(key);
				continue;
			}
			if (!HELD_KEYS.add(key)) continue; // still held from before
			if (mc.screen != null) continue; // never react while typing in chat / menus
			if (key == ClientSettings.HIDE_KEY.get()) {
				hidden = !hidden;
				if (!hidden) dev.zero.hud.Notifications.push("ZER\u00D8 visible", "Everything is drawn again", dev.zero.hud.Notifications.Type.INFO, 1500);
				continue;
			}
			if (key == ClientSettings.GUI_KEY.get()) {
				dev.zero.gui.IntroScreen.openGui();
				continue;
			}
			for (Module m : ModuleManager.all()) {
				if (m.keybind.get() == key) {
					m.toggle();
					ConfigManager.markDirty();
				}
			}
		}
	}

	private static void render3D(MultiBufferSource.BufferSource buffers) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null || hidden) return;
		float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		long t0 = System.nanoTime();
		Render3D.begin();
		for (Module m : ModuleManager.all()) {
			if (m.isEnabled()) {
				try {
					long tm = System.nanoTime();
					m.onRender3D(pt);
					Perf.module(m.getName(), System.nanoTime() - tm);
				} catch (Exception e) {
					LOGGER.error("Error rendering {}", m.getName(), e);
				}
			}
		}
		if (ModuleManager.xray.isEnabled()) ModuleManager.xray.pickLookTarget();
		long tf = System.nanoTime();
		Render3D.flush(buffers);
		Perf.flush(System.nanoTime() - tf);
		Perf.render(System.nanoTime() - t0);
	}
}
