package dev.zero.seed;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * A sandbox {@link WorldGenLevel} made of 3x3 private chunk copies. Vanilla ore features run
 * against it exactly like during real world generation, but only these copies are modified.
 * Nothing here touches the real client world or the network.
 */
final class SimLevel implements WorldGenLevel {
	private final long seed;
	private final int centerX, centerZ, minY, height;
	private final ChunkAccess[] chunks; // 3x3, index (dx+1) + (dz+1)*3
	private final BiomeManager biomeManager;
	private final int[] surface; // per column of the 48x48 area: estimated ocean-floor height, or MIN for unknown

	SimLevel(long seed, int centerX, int centerZ, int minY, int height, ChunkAccess[] chunks, BiomeManager biomeManager, int[] surface) {
		this.seed = seed;
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.minY = minY;
		this.height = height;
		this.chunks = chunks;
		this.biomeManager = biomeManager;
		this.surface = surface;
	}

	@Nullable ChunkAccess chunk(int cx, int cz) {
		int dx = cx - centerX, dz = cz - centerZ;
		if (dx < -1 || dx > 1 || dz < -1 || dz > 1) return null;
		return chunks[(dx + 1) + (dz + 1) * 3];
	}

	// ---- What ore generation actually uses ------------------------------------------------------

	@Override public long getSeed() { return seed; }

	@Override public int getMinY() { return minY; }

	@Override public int getHeight() { return height; }

	@Override public BiomeManager getBiomeManager() { return biomeManager; }

	@Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) { return biomeManager.getNoiseBiomeAtQuart(x, y, z); }

	@Override public Holder<Biome> getNoiseBiome(int x, int y, int z) { return biomeManager.getNoiseBiomeAtQuart(x, y, z); }

	@Override
	public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean required) {
		ChunkAccess c = chunk(x, z);
		if (c == null && required) throw new IllegalStateException("Outside simulation area");
		return c;
	}

	@Override public boolean hasChunk(int x, int z) { return chunk(x, z) != null; }

	@Override
	public boolean ensureCanWrite(BlockPos pos) {
		// Same as WorldGenRegion: only the horizontal write radius matters (not the Y level).
		return chunk(pos.getX() >> 4, pos.getZ() >> 4) != null;
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		if (isOutsideBuildHeight(pos)) return Blocks.VOID_AIR.defaultBlockState();
		ChunkAccess c = chunk(pos.getX() >> 4, pos.getZ() >> 4);
		if (c == null) return Blocks.STONE.defaultBlockState();
		LevelChunkSection s = c.getSection(c.getSectionIndex(pos.getY()));
		return s.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
	}

	@Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }

	@Override
	public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
		if (!ensureCanWrite(pos) || isOutsideBuildHeight(pos)) return false;
		ChunkAccess c = chunk(pos.getX() >> 4, pos.getZ() >> 4);
		c.getSection(c.getSectionIndex(pos.getY())).setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state, false);
		return true;
	}

	@Override
	public int getHeight(Heightmap.Types type, int x, int z) {
		int ax = x - ((centerX - 1) << 4), az = z - ((centerZ - 1) << 4);
		if (ax < 0 || az < 0 || ax >= 48 || az >= 48) return minY + height;
		int h = surface[ax + az * 48];
		return h == Integer.MIN_VALUE ? minY + height : h;
	}

	@Override public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) { return predicate.test(getBlockState(pos)); }

	@Override public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) { return predicate.test(getFluidState(pos)); }

	@Override public BlockPos getHeightmapPos(Heightmap.Types type, BlockPos pos) { return new BlockPos(pos.getX(), getHeight(type, pos.getX(), pos.getZ()), pos.getZ()); }

	@Override public boolean removeBlock(BlockPos pos, boolean moving) { return setBlock(pos, Blocks.AIR.defaultBlockState(), 3, 512); }

	@Override public boolean destroyBlock(BlockPos pos, boolean drop, @Nullable Entity entity, int recursion) { return removeBlock(pos, false); }

	@Override public boolean isClientSide() { return false; }

	@Override public int getSeaLevel() { return 63; }

	// ---- Not used by ore generation: inert stubs ------------------------------------------------

	private static UnsupportedOperationException unsupported() { return new UnsupportedOperationException("Not available in ZERØ seed simulation"); }

	@Override public ServerLevel getLevel() { throw unsupported(); }
	@Override public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) { throw unsupported(); }
	@Override public long nextSubTickCount() { return 0; }
	@Override public LevelData getLevelData() { throw unsupported(); }
	@Override public @Nullable MinecraftServer getServer() { return null; }
	@Override public ChunkSource getChunkSource() { throw unsupported(); }
	@Override public RandomSource getRandom() { return RandomSource.create(0); }
	@Override public void playSound(@Nullable Entity entity, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {}
	@Override public void addParticle(ParticleOptions particle, double x, double y, double z, double vx, double vy, double vz) {}
	@Override public void levelEvent(@Nullable Entity entity, int event, BlockPos pos, int data) {}
	@Override public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {}
	@Override public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay, TickPriority priority) { return new ScheduledTick<>(type, pos, 0, priority, 0); }
	@Override public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay) { return new ScheduledTick<>(type, pos, 0, TickPriority.NORMAL, 0); }
	@Override public LevelTickAccess<Block> getBlockTicks() { throw unsupported(); }
	@Override public LevelTickAccess<Fluid> getFluidTicks() { throw unsupported(); }
	@Override public int getSkyDarken() { return 0; }
	@Override public DimensionType dimensionType() { throw unsupported(); }
	@Override public RegistryAccess registryAccess() { throw unsupported(); }
	@Override public FeatureFlagSet enabledFeatures() { throw unsupported(); }
	@Override public EnvironmentAttributeReader environmentAttributes() { throw unsupported(); }
	@Override public @Nullable BlockEntity getBlockEntity(BlockPos pos) { return null; }
	@Override public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) { return Optional.empty(); }
	@Override public WorldBorder getWorldBorder() { throw unsupported(); }
	@Override public @Nullable BlockGetter getChunkForCollisions(int x, int z) { return null; }
	@Override public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB box) { return List.of(); }
	@Override public float getShade(Direction direction, boolean shade) { return 1; }
	@Override public LevelLightEngine getLightEngine() { throw unsupported(); }
	@Override public int getBlockTint(BlockPos pos, ColorResolver resolver) { return 0; }
	@Override public List<Entity> getEntities(@Nullable Entity except, AABB box, Predicate<? super Entity> predicate) { return List.of(); }
	@Override public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> predicate) { return List.of(); }
	@Override public List<? extends Player> players() { return List.of(); }
}
