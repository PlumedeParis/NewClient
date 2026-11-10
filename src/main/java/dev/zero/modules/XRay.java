package dev.zero.modules;

import dev.zero.module.Category;
import dev.zero.module.Module;
import dev.zero.setting.BlockListSetting;
import dev.zero.setting.BoolSetting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Renders only the chosen blocks (ores...) and hides everything else. Pure rendering change.
 * <p>
 * About anti-xray (2b2t): the server hides real ores that are fully enclosed and sends fake
 * ones instead, so NO client-side X-Ray can reveal enclosed ores. Blocks touching air, water or
 * lava must be sent truthfully, so "Exposed Only" shows the real ores in caves / ravines /
 * tunnels and filters out the fake noise.
 */
public class XRay extends Module {
	public final BlockListSetting blocks = add(new BlockListSetting("Blocks", "Blocks that stay visible", defaults())).onChange(this::reload);
	private final BoolSetting exposedOnly = add(new BoolSetting("Exposed Only", "Only show blocks touching air/water/lava (defeats anti-xray fake ores)", true)).onChange(this::reload);
	private final BoolSetting fluids = add(new BoolSetting("Fluids", "Keep water and lava visible", false)).onChange(this::reload);
	private final BoolSetting fullbright = add(new BoolSetting("Fullbright", "Light every visible block fully", true)).onChange(this::reload);

	// Read from chunk-meshing worker threads.
	private static volatile boolean active;
	private static volatile Set<Block> visible = Set.of();
	private static volatile boolean exposed, showFluids, bright;

	public XRay() {
		super("XRay", "See ores through terrain (exposed-only mode beats anti-xray fakes)", Category.RENDER);
	}

	private static List<Block> defaults() {
		List<Block> list = new ArrayList<>();
		for (Block b : BuiltInRegistries.BLOCK) {
			String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
			if (id.endsWith("_ore")) list.add(b);
		}
		list.add(Blocks.ANCIENT_DEBRIS);
		list.add(Blocks.SPAWNER);
		return list;
	}

	@Override
	public String getInfo() { return exposedOnly.get() ? "Exposed" : null; }

	private void sync() {
		visible = Set.copyOf(blocks.get());
		exposed = exposedOnly.get();
		showFluids = fluids.get();
		bright = fullbright.get();
	}

	private void reload() {
		sync();
		if (isEnabled() && mc.levelRenderer != null) mc.levelRenderer.allChanged();
	}

	@Override
	protected void onEnable() {
		sync();
		active = true;
		if (mc.levelRenderer != null) mc.levelRenderer.allChanged();
	}

	@Override
	protected void onDisable() {
		active = false;
		if (mc.levelRenderer != null) mc.levelRenderer.allChanged();
	}

	// ---- Hooks used by the rendering mixins (any thread) --------------------------------------

	public static boolean isActive() { return active; }

	public static boolean fullbright() { return active && bright; }

	public static boolean renderFluids() { return showFluids; }

	public static boolean isTarget(BlockState state) { return visible.contains(state.getBlock()); }

	/** Whether a block's model is meshed while X-Ray is on. */
	public static boolean shouldRender(BlockState state, BlockPos pos, BlockGetter level) {
		if (!visible.contains(state.getBlock())) return false;
		return !exposed || isExposed(pos, level);
	}

	/** True if any neighbor is air, fluid or see-through: such blocks can't be faked by anti-xray. */
	public static boolean isExposed(BlockPos pos, BlockGetter level) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (Direction d : Direction.values()) {
			BlockState n = level.getBlockState(m.setWithOffset(pos, d));
			if (n.isAir() || !n.getFluidState().isEmpty() || !n.isSolidRender()) return true;
		}
		return false;
	}
}
