package dev.zero.module;

import dev.zero.config.FindsStore;
import dev.zero.hud.Notifications;
import dev.zero.setting.BoolSetting;
import dev.zero.util.ChatUtil;
import dev.zero.util.Sounds;
import dev.zero.util.WorldUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Base for modules that analyse each loaded chunk into a result object, re-analyse chunks
 * whose blocks change, and alert / save once per location.
 */
public abstract class ChunkFinderModule<R> extends ScanningModule {
	protected final Long2ObjectOpenHashMap<R> results = new Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet dirty = new LongOpenHashSet();
	private final java.util.Set<String> alerted = new java.util.HashSet<>();
	private int tick;

	protected final BoolSetting notify = add(new BoolSetting("Notify", "Toast + local chat message on finds", true));
	protected final BoolSetting sound = add(new BoolSetting("Sound", "Local sound on finds", true));
	protected final BoolSetting save = add(new BoolSetting("Save Finds", "Save finds to the ★ Finds list", true));

	protected ChunkFinderModule(String name, String description, Category category) {
		super(name, description, category);
	}

	/** Analyse a chunk; return null when there is nothing to report. */
	protected abstract R analyse(LevelChunk chunk);

	/** Whether a block change can affect the analysis. */
	protected boolean relevant(BlockState oldState, BlockState newState) { return true; }

	@Override
	public final void scanChunk(LevelChunk chunk) {
		long key = chunk.getPos().toLong();
		R r = analyse(chunk);
		if (r == null) results.remove(key);
		else results.put(key, r);
	}

	@Override
	public void onBlockChanged(LevelChunk chunk, BlockPos pos, BlockState oldState, BlockState newState) {
		if (relevant(oldState, newState)) dirty.add(chunk.getPos().toLong());
	}

	@Override
	public void onTick() {
		if (++tick % 20 == 0 && !dirty.isEmpty()) {
			for (long key : dirty.toLongArray()) {
				LevelChunk c = mc.level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false);
				if (c != null) scanChunk(c);
			}
			dirty.clear();
		}
	}

	@Override
	public void onChunkUnload(ChunkPos pos) {
		results.remove(pos.toLong());
	}

	@Override
	public void clearAll() {
		results.clear();
		dirty.clear();
	}

	@Override
	public String getInfo() { return results.isEmpty() ? null : String.valueOf(results.size()); }

	/** Toast / chat / sound / save, once per chunk per session and once per ~48 blocks overall. */
	protected void report(ChunkPos chunk, BlockPos at, String title, String details, int color) {
		if (!alerted.add(title + "@" + chunk.toLong())) return;
		String dim = WorldUtil.dimensionName(mc.level);
		if (FindsStore.nearWithPrefix(dim, at.getX(), at.getZ(), 32, title + ":") != null) return;
		if (save.get()) FindsStore.add(new FindsStore.Find(FindsStore.Kind.SUS, at.getX(), at.getY(), at.getZ(), dim, title + ": " + details, System.currentTimeMillis()), true);
		if (notify.get()) {
			Notifications.push(title, WorldUtil.fmt(at) + "  •  " + details, Notifications.Type.FOUND, 6000);
			ChatUtil.info(Component.literal(title + " ").withStyle(s -> s.withColor(color & 0xFFFFFF).withBold(true))
				.append(Component.literal("at " + WorldUtil.fmt(at) + " ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal("(" + details + ")").withStyle(ChatFormatting.GRAY)));
		}
		if (sound.get()) Sounds.ping();
	}
}
