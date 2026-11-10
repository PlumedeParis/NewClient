package dev.newclient.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/** Local UI sounds only (played by the client's sound engine, never sent anywhere). */
public final class Sounds {
	private Sounds() {}

	public static void play(Holder<SoundEvent> sound, float pitch, float volume) {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound.value(), pitch, volume));
	}

	public static void ping() { play(SoundEvents.NOTE_BLOCK_PLING, 1.6f, 0.8f); }

	public static void alert() { play(SoundEvents.NOTE_BLOCK_BELL, 0.9f, 1f); }

	public static void click() { play(SoundEvents.UI_BUTTON_CLICK, 1.4f, 0.25f); }
}
