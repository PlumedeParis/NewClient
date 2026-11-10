package dev.zero.seed;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.ScatteredOreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vanilla 1.21.11 world generation for one seed + dimension, rebuilt locally from the
 * worldgen registries that ship inside the client jar. Used only to predict ores.
 */
public final class SeedWorld {
	private static HolderLookup.Provider lookup;

	/** One ore feature, in the exact order vanilla decorates a chunk. */
	record OreTask(PlacedFeature feature, int step, int index, Set<Block> ores) {}

	final long seed;
	final NoiseBasedChunkGenerator generator;
	final BiomeManager biomeManager;
	final List<OreTask> tasks = new ArrayList<>();
	/** Every ore block the simulated features can place, mapped to the stone it replaced. */
	final Map<Block, Block> oreBase = new IdentityHashMap<>();
	final Set<Block> allOres;

	private static synchronized HolderLookup.Provider lookup() {
		if (lookup == null) lookup = VanillaRegistries.createLookup();
		return lookup;
	}

	public static SeedWorld create(long seed, boolean nether, int minY, int height) {
		return new SeedWorld(seed, nether, minY, height);
	}

	private SeedWorld(long seed, boolean nether, int minY, int height) {
		this.seed = seed;
		HolderLookup.Provider l = lookup();
		ResourceKey<MultiNoiseBiomeSourceParameterList> preset = nether ? MultiNoiseBiomeSourceParameterLists.NETHER : MultiNoiseBiomeSourceParameterLists.OVERWORLD;
		ResourceKey<NoiseGeneratorSettings> settingsKey = nether ? NoiseGeneratorSettings.NETHER : NoiseGeneratorSettings.OVERWORLD;
		Holder<MultiNoiseBiomeSourceParameterList> params = l.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(preset);
		Holder<NoiseGeneratorSettings> settings = l.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(settingsKey);
		BiomeSource biomeSource = MultiNoiseBiomeSource.createFromPreset(params);
		this.generator = new NoiseBasedChunkGenerator(biomeSource, settings);
		RandomState randomState = RandomState.create(settings.value(), l.lookupOrThrow(Registries.NOISE), seed);
		Climate.Sampler sampler = randomState.sampler();
		int minQ = minY >> 2, maxQ = (minY + height - 1) >> 2;
		this.biomeManager = new BiomeManager((qx, qy, qz) -> biomeSource.getNoiseBiome(qx, Mth.clamp(qy, minQ, maxQ), qz, sampler), BiomeManager.obfuscateSeed(seed));

		// Same feature ordering as ChunkGenerator#featuresPerStep.
		List<Holder<Biome>> biomes = List.copyOf(biomeSource.possibleBiomes());
		List<FeatureSorter.StepFeatureData> steps = FeatureSorter.buildFeaturesPerStep(biomes, b -> b.value().getGenerationSettings().features(), true);
		Set<Block> ores = new HashSet<>();
		for (int step = 0; step < steps.size(); step++) {
			List<PlacedFeature> features = steps.get(step).features();
			for (int index = 0; index < features.size(); index++) {
				PlacedFeature pf = features.get(index);
				ConfiguredFeature<?, ?> cf = pf.feature().value();
				if (!(cf.feature() instanceof OreFeature || cf.feature() instanceof ScatteredOreFeature)) continue;
				if (!(cf.config() instanceof OreConfiguration oc)) continue;
				Set<Block> placed = new HashSet<>();
				for (OreConfiguration.TargetBlockState t : oc.targetStates) {
					Block b = t.state.getBlock();
					if (isOre(b)) placed.add(b);
				}
				if (placed.isEmpty()) continue; // dirt / gravel / granite / tuff blobs are part of the real terrain already
				tasks.add(new OreTask(pf, step, index, placed));
				for (Block b : placed) oreBase.put(b, baseOf(b));
				ores.addAll(placed);
			}
		}
		this.allOres = Set.copyOf(ores);
	}

	static boolean isOre(Block b) {
		String id = BuiltInRegistries.BLOCK.getKey(b).getPath();
		return id.endsWith("_ore") || b == Blocks.ANCIENT_DEBRIS;
	}

	private static Block baseOf(Block ore) {
		String id = BuiltInRegistries.BLOCK.getKey(ore).getPath();
		if (id.startsWith("deepslate_")) return Blocks.DEEPSLATE;
		if (id.startsWith("nether_") || ore == Blocks.ANCIENT_DEBRIS) return Blocks.NETHERRACK;
		return Blocks.STONE;
	}

	/** Debug: name of each task's placed feature. */
	public List<String> taskNames() {
		var reg = lookup().lookupOrThrow(Registries.PLACED_FEATURE);
		List<String> names = new ArrayList<>();
		for (OreTask t : tasks) {
			String n = reg.listElements().filter(h -> h.value() == t.feature()).map(h -> h.key().identifier().getPath()).findFirst().orElse("?");
			names.add(n + "#" + t.step() + "/" + t.index());
		}
		return names;
	}

	/** Debug: only run the task with this position in the list (-1 = all). */
	public volatile int onlyTask = -1;

	/** Places every simulated ore feature of the center chunk into the sandbox, in vanilla order. */
	void decorate(SimLevel level, int chunkX, int chunkZ, int minY) {
		WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
		BlockPos origin = SectionPos.of(new ChunkPos(chunkX, chunkZ), SectionPos.blockToSectionCoord(minY)).origin();
		long decorationSeed = random.setDecorationSeed(seed, origin.getX(), origin.getZ());
		for (int ti = 0; ti < tasks.size(); ti++) {
			OreTask task = tasks.get(ti);
			if (onlyTask >= 0 && onlyTask != ti) continue;
			random.setFeatureSeed(decorationSeed, task.index(), task.step());
			try {
				task.feature().placeWithBiomeCheck(level, generator, random, origin);
			} catch (RuntimeException ignored) {
				// A feature that needs something the sandbox can't provide is skipped.
			}
		}
	}

	/** Replaces every simulated ore in a section with the stone it was generated in. */
	void stripOres(LevelChunkSection section) {
		if (section.hasOnlyAir() || !section.getStates().maybeHas(s -> oreBase.containsKey(s.getBlock()))) return;
		for (int y = 0; y < 16; y++)
			for (int z = 0; z < 16; z++)
				for (int x = 0; x < 16; x++) {
					Block base = oreBase.get(section.getBlockState(x, y, z).getBlock());
					if (base != null) section.setBlockState(x, y, z, base.defaultBlockState(), false);
				}
	}

	/** Collects the ores the sandbox now contains. */
	void collect(ChunkAccess chunk, Set<Block> wanted, List<Prediction> out) {
		LevelChunkSection[] sections = chunk.getSections();
		int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s.hasOnlyAir() || !s.getStates().maybeHas(st -> wanted.contains(st.getBlock()))) continue;
			int by = chunk.getSectionYFromSectionIndex(i) << 4;
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) {
						Block b = s.getBlockState(x, y, z).getBlock();
						if (wanted.contains(b)) out.add(new Prediction(new BlockPos(bx + x, by + y, bz + z), b));
					}
		}
	}

	public record Prediction(BlockPos pos, Block block) {}
}
