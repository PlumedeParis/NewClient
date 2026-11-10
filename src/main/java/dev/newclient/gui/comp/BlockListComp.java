package dev.newclient.gui.comp;

import dev.newclient.gui.BlockPickerScreen;
import dev.newclient.gui.Theme;
import dev.newclient.render.Render2D;
import dev.newclient.setting.BlockListSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

public class BlockListComp extends SettingComp<BlockListSetting> {
	public BlockListComp(BlockListSetting s) { super(s); }

	@Override public int height() { return 26; }

	@Override
	protected void render(GuiGraphics g, int mx, int my) {
		if (hovered(mx, my)) Render2D.rect(g, x, y, w, height(), Theme.ROW_HOVER);
		Render2D.text(g, setting.getName(), x + 6, y + 3, Theme.TEXT, false);
		String v = setting.get().size() + " ›";
		Render2D.text(g, v, x + w - 6 - Render2D.width(v), y + 3, Theme.accent(), false);
		// Preview of the first selected blocks as small item icons (scaled 0.5).
		int i = 0;
		for (Block b : setting.get()) {
			ItemStack stack = new ItemStack(b.asItem());
			if (stack.isEmpty()) continue;
			g.pose().pushMatrix();
			g.pose().translate(x + 6 + i * 10, y + 14);
			g.pose().scale(0.5f, 0.5f);
			g.renderItem(stack, 0, 0);
			g.pose().popMatrix();
			if (++i * 10 > w - 20) break;
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!hovered(mx, my)) return false;
		Minecraft mc = Minecraft.getInstance();
		mc.setScreen(new BlockPickerScreen(setting, mc.screen));
		return true;
	}
}
