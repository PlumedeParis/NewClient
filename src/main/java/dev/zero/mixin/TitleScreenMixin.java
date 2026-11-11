package dev.zero.mixin;

import dev.zero.hud.TitleBranding;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** "ZERØ client, made by PlumeTheCat" in the top-right corner of the title screen. */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
	protected TitleScreenMixin(Component title) {
		super(title);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void zero$branding(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
		TitleBranding.render(graphics, this.width);
	}
}
