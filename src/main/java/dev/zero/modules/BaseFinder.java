package dev.zero.modules;

import dev.zero.config.ClientSettings;
import dev.zero.config.FindsStore;
import dev.zero.config.FindsStore.Find;
import dev.zero.config.FindsStore.Kind;
import dev.zero.hud.Notifications;
import dev.zero.module.Category;
import dev.zero.module.ScanningModule;
import dev.zero.render.Render2D;
import dev.zero.render.Render3D;
import dev.zero.setting.BoolSetting;
import dev.zero.setting.NumberSetting;
import dev.zero.util.ChatUtil;
import dev.zero.util.ColorUtil;
import dev.zero.util.Sounds;
import dev.zero.util.WorldUtil;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds player bases and stashes by scoring every loaded chunk for blocks and entities
 * that never (or rarely) generate naturally, while filtering out natural structures
 * (villages, dungeons, bastions, ancient cities, end cities, trial chambers, ruined portals...).
 * <p>
 * 100% passive: it only analyses chunk/entity data the server already sent.
 */
public class BaseFinder extends ScanningModule {
	// ---- Settings -------------------------------------------------------------------------
	private final NumberSetting baseScore = add(new NumberSetting("Base Score", "Score needed to flag a chunk as a base", 40, 10, 200, 1)).onChange(this::reclassifyAll);
	private final NumberSetting stashSize = add(new NumberSetting("Stash Size", "Storage blocks in one chunk to flag a stash", 8, 3, 64, 1)).onChange(this::reclassifyAll);
	private final BoolSetting possible = add(new BoolSetting("Show Possible", "Also show weak matches (half the base score)", true)).onChange(this::reclassifyAll);
	private final BoolSetting countEntities = add(new BoolSetting("Entities", "Count item frames, armor stands, chest boats...", true));
	private final BoolSetting filterStructures = add(new BoolSetting("Filter Structures", "Ignore natural structures (villages, dungeons, bastions...)", true)).onChange(this::rescan);
	private final BoolSetting highlightChunks = add(new BoolSetting("Chunk Boxes", "Box around the detected blocks of each chunk", true));
	private final BoolSetting highlightBlocks = add(new BoolSetting("Block Boxes", "Highlight each storage / player block", true));
	private final BoolSetting tracers = add(new BoolSetting("Tracers", "Tracers to detected bases", true));
	private final BoolSetting waypoints = add(new BoolSetting("Waypoints", "Show saved finds as beams, even when unloaded", true));
	private final NumberSetting waypointRange = add(new NumberSetting("Waypoint Range", "Max distance for saved waypoints", 5000, 200, 100000, 100, "m")).visibleWhen(waypoints::get);
	private final BoolSetting notify = add(new BoolSetting("Notify", "Toast when something is found", true));
	private final BoolSetting chat = add(new BoolSetting("Chat Message", "Local chat message with coordinates", true));
	private final BoolSetting sound = add(new BoolSetting("Sound", "Play a local sound on finds", true));
	private final BoolSetting save = add(new BoolSetting("Save Finds", "Save finds to config/zero/finds", true));
	private final BoolSetting hudList = add(new BoolSetting("HUD List", "List nearby finds on the HUD", true));

	// ---- Rules ----------------------------------------------------------------------------
	private static final int STORAGE = 1, STRONG = 2, VILLAGE_COMMON = 4, STRUCTURE_COMMON = 8;

	private record Rule(String label, float weight, int cap, int flags) {
		boolean has(int flag) { return (flags & flag) != 0; }
	}

	private static final Reference2ObjectOpenHashMap<Block, Rule> RULES = new Reference2ObjectOpenHashMap<>();
	/** Blocks whose mere presence identifies a natural structure. */
	private static final Set<Block> MARKERS = Set.of(Blocks.BELL, Blocks.SPAWNER, Blocks.GILDED_BLACKSTONE, Blocks.REINFORCED_DEEPSLATE,
		Blocks.SCULK_SHRIEKER, Blocks.VAULT, Blocks.TRIAL_SPAWNER, Blocks.END_PORTAL_FRAME, Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR,
		Blocks.CRYING_OBSIDIAN, Blocks.NETHER_BRICKS, Blocks.MOSSY_COBBLESTONE, Blocks.CHISELED_SANDSTONE, Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL);

	static {
		for (Block b : BuiltInRegistries.BLOCK) {
			String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
			Rule r = classify(id);
			if (r != null) RULES.put(b, r);
		}
	}

	private static Rule classify(String id) {
		if (id.endsWith("shulker_box")) return new Rule("Shulker", 14, 40, STORAGE | STRONG);
		if (id.equals("ender_chest")) return new Rule("Ender Chest", 9, 6, STORAGE | STRONG);
		if (id.equals("chest") || id.equals("trapped_chest")) return new Rule("Chest", 3, 60, STORAGE | STRUCTURE_COMMON | VILLAGE_COMMON);
		if (id.equals("barrel")) return new Rule("Barrel", 3, 40, STORAGE | VILLAGE_COMMON | STRUCTURE_COMMON);
		if (id.equals("hopper")) return new Rule("Hopper", 5, 10, STRONG);
		if (id.equals("beacon")) return new Rule("Beacon", 30, 4, STRONG);
		if (id.equals("conduit")) return new Rule("Conduit", 15, 2, STRONG);
		if (id.equals("respawn_anchor")) return new Rule("Respawn Anchor", 12, 3, STRONG);
		if (id.equals("lodestone")) return new Rule("Lodestone", 12, 3, STRONG);
		if (id.equals("enchanting_table")) return new Rule("Enchanting Table", 10, 2, STRONG);
		if (id.equals("nether_portal")) return new Rule("Nether Portal", 1.5f, 12, STRONG);
		if (id.endsWith("_sign") || id.endsWith("_hanging_sign")) return new Rule("Sign", 5, 8, STRONG);
		if (id.endsWith("_bed")) return new Rule("Bed", 5, 4, VILLAGE_COMMON);
		if (id.endsWith("_banner")) return new Rule("Banner", 3, 6, STRUCTURE_COMMON);
		if (id.contains("anvil")) return new Rule("Anvil", 6, 3, VILLAGE_COMMON);
		if (id.equals("brewing_stand")) return new Rule("Brewing Stand", 4, 3, VILLAGE_COMMON | STRUCTURE_COMMON);
		if (id.equals("jukebox") || id.equals("note_block")) return new Rule("Music", 4, 3, STRONG);
		if (id.equals("crafter") || id.equals("observer")) return new Rule("Redstone", 5, 8, STRONG);
		if (id.equals("repeater") || id.equals("comparator") || id.equals("daylight_detector") || id.equals("redstone_lamp") || id.equals("target")) return new Rule("Redstone", 4, 8, STRONG);
		if (id.equals("piston") || id.equals("sticky_piston")) return new Rule("Piston", 3, 6, STRUCTURE_COMMON);
		if (id.equals("dispenser") || id.equals("dropper")) return new Rule("Dispenser", 3, 6, STRUCTURE_COMMON);
		if (id.equals("furnace") || id.equals("blast_furnace") || id.equals("smoker")) return new Rule("Furnace", 2, 8, VILLAGE_COMMON);
		if (id.equals("crafting_table")) return new Rule("Crafting Table", 2, 3, VILLAGE_COMMON);
		if (id.equals("lectern") || id.equals("loom") || id.equals("cartography_table") || id.equals("smithing_table") || id.equals("fletching_table")
			|| id.equals("grindstone") || id.equals("stonecutter") || id.equals("composter")) return new Rule("Workstation", 2, 6, VILLAGE_COMMON);
		if (id.endsWith("_concrete")) return new Rule("Concrete", 1, 25, 0);
		if (id.endsWith("_glazed_terracotta")) return new Rule("Glazed Terracotta", 1, 15, STRUCTURE_COMMON);
		if (id.contains("stained_glass")) return new Rule("Stained Glass", 0.5f, 15, 0);
		if (id.endsWith("_wool") || id.endsWith("_carpet")) return new Rule("Wool", 0.3f, 6, VILLAGE_COMMON | STRUCTURE_COMMON);
		if (id.startsWith("quartz_") || id.equals("smooth_quartz") || id.equals("chiseled_quartz_block")) return new Rule("Quartz", 0.5f, 12, 0);
		if (id.equals("netherite_block") || id.equals("diamond_block") || id.equals("emerald_block")) return new Rule("Valuable Block", 10, 30, STRONG);
		return null;
	}

	// ---- State ----------------------------------------------------------------------------
	private static final class ChunkResult {
		float blockScore, entityScore;
		int storage, entityStorage;
		final Object2IntLinkedOpenHashMap<String> counts = new Object2IntLinkedOpenHashMap<>();
		final List<BlockPos> highlights = new ArrayList<>();
		AABB bounds;
		BlockPos center;
		Kind kind;

		float score() { return blockScore + entityScore; }
		int storageTotal() { return storage + entityStorage; }
	}

	private final Long2ObjectOpenHashMap<ChunkResult> results = new Long2ObjectOpenHashMap<>();
	private final Long2FloatOpenHashMap entityScores = new Long2FloatOpenHashMap();
	private final LongOpenHashSet dirty = new LongOpenHashSet();
	private int tickCounter;
	private int foundCount;

	public BaseFinder() {
		super("BaseFinder", "Detects bases and stashes from player-placed blocks", Category.HUNT);
	}

	@Override
	public String getInfo() { return foundCount > 0 ? String.valueOf(foundCount) : null; }

	@Override
	protected void onEnable() {
		FindsStore.ensureLoaded();
		super.onEnable();
	}

	// ---- Scanning -------------------------------------------------------------------------
	@Override
	public void scanChunk(LevelChunk chunk) {
		String dim = WorldUtil.dimensionName(mc.level);
		boolean nether = dim.equals("the_nether"), end = dim.equals("the_end"), overworld = dim.equals("overworld");
		Reference2IntOpenHashMap<Block> raw = new Reference2IntOpenHashMap<>();
		List<BlockPos> positions = new ArrayList<>();
		LevelChunkSection[] sections = chunk.getSections();
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s == null || s.hasOnlyAir()) continue;
			if (!s.getStates().maybeHas(st -> RULES.containsKey(st.getBlock()) || MARKERS.contains(st.getBlock()) || st.is(Blocks.OBSIDIAN))) continue;
			int by = chunk.getSectionYFromSectionIndex(i) << 4;
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) {
						Block b = s.getBlockState(x, y, z).getBlock();
						Rule rule = RULES.get(b);
						if (rule != null || MARKERS.contains(b) || b == Blocks.OBSIDIAN) {
							raw.addTo(b, 1);
							if (rule != null && (rule.has(STORAGE) || rule.has(STRONG)) && positions.size() < 400)
								positions.add(new BlockPos(bx + x, by + y, bz + z));
						}
					}
		}

		long key = chunk.getPos().toLong();
		if (raw.isEmpty()) {
			results.remove(key);
			return;
		}

		// --- Natural structure detection ---
		boolean filter = filterStructures.get();
		boolean village = filter && raw.containsKey(Blocks.BELL);
		boolean dungeonLike = filter && (raw.containsKey(Blocks.SPAWNER) || raw.containsKey(Blocks.TRIAL_SPAWNER) || raw.containsKey(Blocks.VAULT));
		boolean bastion = filter && raw.containsKey(Blocks.GILDED_BLACKSTONE);
		boolean ancientCity = filter && (raw.containsKey(Blocks.REINFORCED_DEEPSLATE) || raw.containsKey(Blocks.SCULK_SHRIEKER));
		boolean stronghold = filter && raw.containsKey(Blocks.END_PORTAL_FRAME);
		boolean endCity = filter && end && (raw.containsKey(Blocks.PURPUR_BLOCK) || raw.containsKey(Blocks.PURPUR_PILLAR));
		boolean fortress = filter && nether && raw.containsKey(Blocks.NETHER_BRICKS);
		boolean ruinedPortal = filter && raw.containsKey(Blocks.CRYING_OBSIDIAN) && raw.getInt(Blocks.CRYING_OBSIDIAN) < 6;
		boolean temple = filter && (raw.containsKey(Blocks.CHISELED_SANDSTONE) || raw.containsKey(Blocks.MOSSY_COBBLESTONE)
			|| raw.containsKey(Blocks.SUSPICIOUS_SAND) || raw.containsKey(Blocks.SUSPICIOUS_GRAVEL));
		boolean structure = dungeonLike || bastion || ancientCity || stronghold || endCity || fortress || ruinedPortal || temple;

		ChunkResult r = new ChunkResult();
		for (var e : raw.reference2IntEntrySet()) {
			Block b = e.getKey();
			int n = e.getIntValue();
			Rule rule = RULES.get(b);
			if (b == Blocks.OBSIDIAN) {
				if (ruinedPortal || end) continue;
				rule = new Rule("Obsidian", overworld ? 0.5f : 1f, 10, 0);
			}
			if (rule == null) continue;
			if (village && rule.has(VILLAGE_COMMON)) continue;
			if (structure && rule.has(STRUCTURE_COMMON)) {
				// Natural structures contain a few chests / barrels; only count the excess.
				int allowance = bastion || ancientCity || endCity ? 12 : 4;
				n -= allowance;
				if (n <= 0) continue;
			}
			if (b == Blocks.NETHER_PORTAL && end) continue;
			r.blockScore += Math.min(n, rule.cap) * rule.weight;
			r.counts.addTo(rule.label, n);
			if (rule.has(STORAGE)) r.storage += n;
		}

		if (r.counts.isEmpty()) {
			results.remove(key);
			return;
		}
		// Bounding box / center of the interesting blocks.
		List<BlockPos> kept = new ArrayList<>();
		for (BlockPos p : positions) {
			Rule rule = RULES.get(chunk.getBlockState(p).getBlock());
			if (rule == null) continue;
			if (village && rule.has(VILLAGE_COMMON)) continue;
			kept.add(p);
		}
		r.highlights.addAll(kept);
		computeBounds(r, chunk.getPos());
		ChunkResult old = results.get(key);
		if (old != null) {
			r.entityScore = old.entityScore;
			r.entityStorage = old.entityStorage;
		}
		results.put(key, r);
		classify(key, r);
	}

	private static void computeBounds(ChunkResult r, ChunkPos pos) {
		if (r.highlights.isEmpty()) {
			r.center = new BlockPos(pos.getMiddleBlockX(), 64, pos.getMiddleBlockZ());
			r.bounds = null;
			return;
		}
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
		long sx = 0, sy = 0, sz = 0;
		for (BlockPos p : r.highlights) {
			minX = Math.min(minX, p.getX());
			minY = Math.min(minY, p.getY());
			minZ = Math.min(minZ, p.getZ());
			maxX = Math.max(maxX, p.getX());
			maxY = Math.max(maxY, p.getY());
			maxZ = Math.max(maxZ, p.getZ());
			sx += p.getX();
			sy += p.getY();
			sz += p.getZ();
		}
		int n = r.highlights.size();
		r.center = new BlockPos((int) (sx / n), (int) (sy / n), (int) (sz / n));
		r.bounds = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1).inflate(0.25);
	}

	@Override
	public void onBlockChanged(LevelChunk chunk, BlockPos pos, BlockState oldState, BlockState newState) {
		if (RULES.containsKey(oldState.getBlock()) || RULES.containsKey(newState.getBlock()) || MARKERS.contains(newState.getBlock()))
			dirty.add(chunk.getPos().toLong());
	}

	@Override
	public void onChunkUnload(ChunkPos pos) {
		results.remove(pos.toLong());
		entityScores.remove(pos.toLong());
	}

	@Override
	public void clearAll() {
		results.clear();
		entityScores.clear();
		dirty.clear();
		foundCount = 0;
	}

	private void reclassifyAll() {
		for (var e : results.long2ObjectEntrySet()) {
			e.getValue().kind = null;
			classify(e.getLongKey(), e.getValue());
		}
	}

	@Override
	public void onTick() {
		tickCounter++;
		if (tickCounter % 10 == 0 && !dirty.isEmpty()) {
			for (long key : dirty.toLongArray()) {
				LevelChunk c = mc.level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
				if (c != null) scanChunk(c);
			}
			dirty.clear();
		}
		if (tickCounter % 20 == 0 && countEntities.get()) scanEntities();
	}

	private void scanEntities() {
		String dim = WorldUtil.dimensionName(mc.level);
		boolean end = dim.equals("the_end");
		Long2ObjectOpenHashMap<float[]> perChunk = new Long2ObjectOpenHashMap<>();
		for (Entity e : mc.level.entitiesForRendering()) {
			float w = 0, storage = 0;
			if (e instanceof GlowItemFrame) w = 5;
			else if (e instanceof ItemFrame) w = end ? 0 : 4; // end ships have one elytra frame
			else if (e instanceof ArmorStand) w = 6;
			else if (e instanceof Painting) w = 2;
			else if (e instanceof ContainerEntity) {
				// Chest minecarts spawn in mineshafts, chest boats never generate.
				boolean minecart = e.getType().toString().contains("minecart");
				w = minecart ? 0.5f : 5;
				storage = minecart ? 0 : 1;
			} else if (e instanceof EndCrystal && !end) w = 8;
			if (w == 0) continue;
			float[] v = perChunk.computeIfAbsent(ChunkPos.asLong(e.blockPosition()), k -> new float[2]);
			v[0] += w;
			v[1] += storage;
		}
		for (var entry : results.long2ObjectEntrySet()) {
			float[] v = perChunk.remove(entry.getLongKey());
			ChunkResult r = entry.getValue();
			float newScore = v == null ? 0 : Math.min(v[0], 60);
			int newStorage = v == null ? 0 : (int) v[1];
			if (newScore != r.entityScore || newStorage != r.entityStorage) {
				r.entityScore = newScore;
				r.entityStorage = newStorage;
				classify(entry.getLongKey(), r);
			}
		}
		// Chunks with only entities (e.g. an item-frame wall) get a result too.
		for (var entry : perChunk.long2ObjectEntrySet()) {
			float[] v = entry.getValue();
			if (v[0] < 8) continue;
			ChunkResult r = new ChunkResult();
			r.entityScore = Math.min(v[0], 60);
			r.entityStorage = (int) v[1];
			r.counts.addTo("Entities", (int) v[0]);
			ChunkPos cp = new ChunkPos(entry.getLongKey());
			computeBounds(r, cp);
			r.center = new BlockPos(cp.getMiddleBlockX(), (int) mc.player.getY(), cp.getMiddleBlockZ());
			results.put(entry.getLongKey(), r);
			classify(entry.getLongKey(), r);
		}
	}

	private void classify(long key, ChunkResult r) {
		Kind kind = null;
		float score = r.score();
		if (r.storageTotal() >= stashSize.getInt()) kind = Kind.STASH;
		else if (score >= baseScore.get()) kind = Kind.BASE;
		else if (possible.get() && score >= baseScore.get() / 2) kind = Kind.POSSIBLE;
		Kind previous = r.kind;
		r.kind = kind;
		if (kind != null && (previous == null || kind.ordinal() < previous.ordinal())) onFound(r, kind);
	}

	private void onFound(ChunkResult r, Kind kind) {
		String dim = WorldUtil.dimensionName(mc.level);
		BlockPos c = r.center;
		Find existing = FindsStore.near(dim, c.getX(), c.getZ(), 64, Kind.STASH, Kind.BASE, Kind.POSSIBLE);
		boolean upgrade = existing != null && kind.ordinal() < existing.kind.ordinal();
		if (existing != null && !upgrade) return;
		if (upgrade) FindsStore.remove(existing);
		foundCount++;
		String summary = summary(r);
		if (save.get()) FindsStore.add(new Find(kind, c.getX(), c.getY(), c.getZ(), dim, summary, System.currentTimeMillis()), true);
		if (kind == Kind.POSSIBLE) return; // weak matches are shown but don't spam alerts
		if (notify.get()) Notifications.push(kind.title + " found!", WorldUtil.fmt(c) + "  •  " + summary, Notifications.Type.FOUND, 7000);
		if (chat.get()) {
			ChatUtil.info(Component.literal(kind.title + " ").withStyle(s -> s.withColor(kind.color & 0xFFFFFF).withBold(true))
				.append(Component.literal("at " + WorldUtil.fmt(c) + " ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal("(" + summary + ")").withStyle(ChatFormatting.GRAY)));
		}
		if (sound.get()) Sounds.ping();
	}

	private static String summary(ChunkResult r) {
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		List<Map.Entry<String, Integer>> list = new ArrayList<>(r.counts.entrySet());
		list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
		for (Map.Entry<String, Integer> e : list) {
			if (shown++ == 3) break;
			if (!sb.isEmpty()) sb.append(", ");
			sb.append(e.getValue()).append(' ').append(e.getKey());
		}
		sb.append(String.format(Locale.ROOT, " [%.0f pts]", r.score()));
		return sb.toString();
	}

	// ---- Rendering ------------------------------------------------------------------------
	@Override
	public void onRender3D(float partialTick) {
		Vec3 cam = WorldUtil.cameraPos();
		LongOpenHashSet drawnChunks = new LongOpenHashSet();
		for (var e : results.long2ObjectEntrySet()) {
			ChunkResult r = e.getValue();
			if (r.kind == null) continue;
			drawnChunks.add(e.getLongKey());
			int color = r.kind.color;
			if (highlightChunks.get() && r.bounds != null) Render3D.box(r.bounds, ColorUtil.withAlpha(color, 28), ColorUtil.withAlpha(color, 200), 2f, true);
			if (highlightBlocks.get()) {
				for (BlockPos p : r.highlights) Render3D.box(new AABB(p).deflate(0.04), ColorUtil.withAlpha(color, 45), ColorUtil.withAlpha(color, 170), 1.2f, true);
			}
			if (tracers.get() && r.kind != Kind.POSSIBLE) Render3D.tracer(Vec3.atCenterOf(r.center), ColorUtil.withAlpha(color, 210), 1.8f);
			Render3D.label(Vec3.atCenterOf(r.center).add(0, r.bounds != null ? r.bounds.maxY - r.center.getY() + 0.6 : 1.5, 0),
				r.kind.title.toUpperCase(Locale.ROOT) + "  §f" + Math.round(r.score()) + "pts §7" + WorldUtil.distance(cam.distanceTo(Vec3.atCenterOf(r.center))), color, 1.15f);
		}
		if (waypoints.get()) {
			String dim = WorldUtil.dimensionName(mc.level);
			double max = waypointRange.get();
			for (Find f : FindsStore.all()) {
				if (!f.dimension.equals(dim) || f.kind == Kind.PLAYER || f.kind == Kind.SUS) continue;
				if (drawnChunks.contains(ChunkPos.asLong(f.x >> 4, f.z >> 4))) continue;
				Vec3 pos = new Vec3(f.x + 0.5, f.y + 0.5, f.z + 0.5);
				if (pos.distanceTo(cam) > max) continue;
				Render3D.waypoint(pos, f.kind.color, f.kind.title);
			}
		}
	}

	@Override
	public void onRender2D(GuiGraphics g, float partialTick) {
		if (!hudList.get()) return;
		List<ChunkResult> list = new ArrayList<>();
		for (ChunkResult r : results.values()) if (r.kind != null) list.add(r);
		if (list.isEmpty()) return;
		Vec3 cam = WorldUtil.cameraPos();
		list.sort((a, b) -> Double.compare(cam.distanceToSqr(Vec3.atCenterOf(a.center)), cam.distanceToSqr(Vec3.atCenterOf(b.center))));
		int rows = Math.min(5, list.size());
		int w = 168, rowH = 11;
		int h = 16 + rows * rowH;
		int[] pos = dev.zero.hud.Hud.slot(w, h);
		int x = pos[0], y = pos[1];
		Render2D.glow(g, x, y, w, h, 5, ColorUtil.withAlpha(Kind.BASE.color, 50), 4);
		Render2D.roundedRect(g, x, y, w, h, 5, 0xE0101016);
		Render2D.gradientH(g, x + 5, y, w - 10, 1, ClientSettings.accent(), Kind.BASE.color);
		Render2D.text(g, "Bases nearby", x + 6, y + 4, 0xFFFFFFFF, false);
		Render2D.text(g, String.valueOf(list.size()), x + w - 6 - Render2D.width(String.valueOf(list.size())), y + 4, Kind.BASE.color, false);
		for (int i = 0; i < rows; i++) {
			ChunkResult r = list.get(i);
			int ry = y + 15 + i * rowH;
			Render2D.rect(g, x + 6, ry + 2, 3, 5, r.kind.color);
			String coords = ClientSettings.STREAMER.get() ? "" : " " + r.center.getX() + " " + r.center.getZ();
			Render2D.text(g, r.kind.title + "§7" + coords, x + 12, ry, 0xFFE6E6F0, false);
			String d = WorldUtil.distance(cam.distanceTo(Vec3.atCenterOf(r.center))) + " " + WorldUtil.direction(cam, r.center.getX(), r.center.getZ());
			Render2D.text(g, d, x + w - 6 - Render2D.width(d), ry, 0xFF9A9AB0, false);
		}
	}
}
