package dev.newclient;

import com.mojang.blaze3d.platform.InputConstants;
import dev.newclient.config.ClientSettings;
import dev.newclient.config.ConfigManager;
import dev.newclient.gui.ClickGuiScreen;
import dev.newclient.hud.Hud;
import dev.newclient.module.Module;
import dev.newclient.module.ModuleManager;
import dev.newclient.render.Render3D;
import dev.newclient.scan.ChunkScanner;
import net.minecraft.client.renderer.MultiBufferSource;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
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
 * NewClient - a strictly client-side utility client.
 * <p>
 * Nothing in this mod sends packets, registers network channels or interacts with the server.
 * It only reads what the server already sent (chunks, entities, tab list) and renders locally.
 */
public final class NewClient implements ClientModInitializer {
	public static final String MOD_ID = "newclient";
	public static final Logger LOGGER = LoggerFactory.getLogger("NewClient");

	private static final Set<Integer> HELD_KEYS = new HashSet<>();
	private static ClientLevel lastLevel;

	@Override
	public void onInitializeClient() {
		ModuleManager.init();
		ConfigManager.load();

		ClientTickEvents.END_CLIENT_TICK.register(NewClient::tick);
		ClientChunkEvents.CHUNK_LOAD.register(ChunkScanner::onChunkLoad);
		ClientChunkEvents.CHUNK_UNLOAD.register(ChunkScanner::onChunkUnload);
		ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, world) -> {
			ChunkScanner.onWorldChange();
			lastLevel = world;
		});
		WorldRenderEvents.END_MAIN.register(context -> render3D((MultiBufferSource.BufferSource) context.consumers()));
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), Hud::render);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ConfigManager.save());

		LOGGER.info("NewClient loaded with {} modules (client-side only)", ModuleManager.all().size());
	}

	private static void tick(Minecraft mc) {
		if (mc.level == null && lastLevel != null) {
			ChunkScanner.onWorldChange();
			lastLevel = null;
		}
		handleKeys(mc);
		if (mc.level != null && mc.player != null) {
			ChunkScanner.tick();
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
			if (key == ClientSettings.GUI_KEY.get()) {
				mc.setScreen(new ClickGuiScreen());
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
		if (mc.level == null || mc.player == null) return;
		float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
		Render3D.begin();
		for (Module m : ModuleManager.all()) {
			if (m.isEnabled()) {
				try {
					m.onRender3D(pt);
				} catch (Exception e) {
					LOGGER.error("Error rendering {}", m.getName(), e);
				}
			}
		}
		Render3D.flush(buffers);
	}
}
