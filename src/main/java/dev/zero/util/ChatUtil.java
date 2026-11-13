package dev.zero.util;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Prints messages into the LOCAL chat HUD only. Nothing here is ever sent to the server.
 */
public final class ChatUtil {
	private ChatUtil() {}

	public static MutableComponent prefix() {
		return Component.literal("")
			.append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.literal("ZER\u00D8").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(0x8B7CFF)).withBold(true)))
			.append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
	}

	public static void info(Component message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui == null) return;
		mc.gui.getChat().addMessage(prefix().append(message));
	}

	/** Find / alert lines: only when "Chat Alerts" is on (off by default, the Alerts screen keeps the history). */
	public static void alert(Component message) {
		if (dev.zero.config.ClientSettings.CHAT_ALERTS.get()) info(message);
	}

	public static void info(String message) {
		info(Component.literal(message).withStyle(ChatFormatting.GRAY));
	}
}
