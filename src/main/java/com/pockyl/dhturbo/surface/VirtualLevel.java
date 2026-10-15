package com.pockyl.dhturbo.surface;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
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
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
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
import net.minecraft.world.ticks.TickPriority;

import com.pockyl.dhturbo.DhTurbo;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A pretend world for running vanilla features without chunks: reads come from the approximate terrain (ground,
 * top block, sea), writes are recorded. {@link WorldGenLevel} is a huge interface; {@link View} implements the
 * handful of methods features and placement modifiers use, default methods run as written on top of them, anything
 * else throws and the feature (or structure piece) is skipped there.
 * <p>
 * A plain class rather than a dynamic proxy: outside the development environment Forge 1.20.1 runs Minecraft with
 * SRG method names, which a proxy dispatching by name would not recognise.
 */
final class VirtualLevel {
    /** Approximate terrain the features are placed on. */
    interface Terrain {
        /** First non-solid Y of the column. */
        int ground(int x, int z);

        /** Block on top of the ground, before snow (vanilla adds snow after vegetation). */
        BlockState top(int x, int z);

        Holder<Biome> biome(int x, int y, int z);

        /**
         * For worlds without a single surface (the Nether, the End): whether the block is rock. Null for height-field
         * terrain, which is rock below {@link #ground} and open above.
         */
        default Boolean solid(int x, int y, int z) {
            return null;
        }

        /** The world's rock and fluid, for {@link #solid} terrain. */
        default BlockState rock() {
            return Blocks.STONE.defaultBlockState();
        }

        default BlockState fluid() {
            return Blocks.WATER.defaultBlockState();
        }
    }

    private static final Map<String, Boolean> REPORTED = new ConcurrentHashMap<>();

    private final Terrain terrain;
    private final long seed;
    private final int minY;
    private final int height;
    private final int seaLevel;
    private final RegistryAccess registries;
    private final DimensionType dimensionType;
    private final Long2ObjectMap<BlockState> writes = new Long2ObjectOpenHashMap<>();
    /**
     * Per column (x, z): one above the highest block placed there. Heightmaps include placed trees, like vanilla's,
     * so a second tree drawn on an occupied spot lands on the first one's crown and fails its survival check instead
     * of growing inside its trunk (that left jungle trees without logs and crashed vanilla's cocoa decorator).
     */
    private final Long2IntMap placedTops = new Long2IntOpenHashMap();
    /**
     * Answers {@code getChunk}: structure pieces mark fences, stairs and walls there for post-processing. Its own
     * blocks are never read; one chunk serves every position.
     */
    private ProtoChunk chunk;
    /** The level features and structure pieces are placed on. */
    final WorldGenLevel view = new View();

    VirtualLevel(Terrain terrain, long seed, int minY, int height, int seaLevel, RegistryAccess registries,
                 DimensionType dimensionType) {
        this.terrain = terrain;
        this.seed = seed;
        this.minY = minY;
        this.height = height;
        this.seaLevel = seaLevel;
        this.registries = registries;
        this.dimensionType = dimensionType;
    }

    Long2ObjectMap<BlockState> writes() {
        return writes;
    }

    BlockState blockState(BlockPos pos) {
        BlockState written = writes.get(pos.asLong());
        if (written != null) {
            return written;
        }
        int y = pos.getY();
        Boolean solid = terrain.solid(pos.getX(), y, pos.getZ());
        if (solid != null) {
            return solid ? terrain.rock() : y < seaLevel ? terrain.fluid() : Blocks.AIR.defaultBlockState();
        }
        int ground = terrain.ground(pos.getX(), pos.getZ());
        if (y < ground - 1) {
            return y < ground - 4 ? Blocks.STONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
        }
        if (y == ground - 1) {
            return ground <= seaLevel ? Blocks.SAND.defaultBlockState() : terrain.top(pos.getX(), pos.getZ());
        }
        return y < seaLevel ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    private int height(Heightmap.Types type, int x, int z) {
        int ground = terrain.ground(x, z);
        int natural = switch (type) {
            case OCEAN_FLOOR, OCEAN_FLOOR_WG -> ground;
            default -> Math.max(ground, seaLevel);
        };
        if (type == Heightmap.Types.OCEAN_FLOOR_WG || type == Heightmap.Types.WORLD_SURFACE_WG) {
            // World generation heightmaps keep the terrain: later placements do not update them (a shipwreck spanning
            // two chunks would otherwise sink in the first and float in the second).
            return natural;
        }
        return Math.max(natural, placedTops.getOrDefault(ChunkPos.asLong(x, z), Integer.MIN_VALUE));
    }

    private void place(BlockPos pos, BlockState state) {
        writes.put(pos.asLong(), state);
        if (!state.isAir()) {
            long column = ChunkPos.asLong(pos.getX(), pos.getZ());
            if (pos.getY() + 1 > placedTops.getOrDefault(column, Integer.MIN_VALUE)) {
                placedTops.put(column, pos.getY() + 1);
            }
        }
    }

    private void remove(BlockPos pos) {
        writes.put(pos.asLong(), Blocks.AIR.defaultBlockState());
    }

    /** Thrown when a feature or piece wants to spawn an entity; its blocks are placed by then. */
    static final class NoEntities extends UnsupportedOperationException {
        NoEntities() {
            super("Virtual level has no entities");
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    private ProtoChunk chunk() {
        if (chunk == null) {
            chunk = new ProtoChunk(ChunkPos.ZERO, UpgradeData.EMPTY, LevelHeightAccessor.create(minY, height),
                    registries.registryOrThrow(Registries.BIOME), null);
        }
        return chunk;
    }

    private static UnsupportedOperationException unsupported(String method) {
        if (REPORTED.putIfAbsent(method, Boolean.TRUE) == null) {
            DhTurbo.LOGGER.info("A feature called unsupported {} on the virtual level; it is skipped there", method);
        }
        return new UnsupportedOperationException("Virtual level does not support " + method);
    }

    /** The {@link WorldGenLevel} side of the virtual level. */
    final class View implements WorldGenLevel {
        // Blocks and fluids.

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return blockState(pos).getFluidState();
        }

        @Override
        public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
            return predicate.test(blockState(pos));
        }

        @Override
        public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
            return predicate.test(blockState(pos).getFluidState());
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
            place(pos, state);
            return true;
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state, int flags) {
            place(pos, state);
            return true;
        }

        @Override
        public boolean removeBlock(BlockPos pos, boolean isMoving) {
            remove(pos);
            return true;
        }

        @Override
        public boolean destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity, int recursionLeft) {
            remove(pos);
            return true;
        }

        @Override
        public boolean destroyBlock(BlockPos pos, boolean dropBlock) {
            remove(pos);
            return true;
        }

        @Override
        public boolean destroyBlock(BlockPos pos, boolean dropBlock, @Nullable Entity entity) {
            remove(pos);
            return true;
        }

        @Override
        @Nullable
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) {
            return Optional.empty();
        }

        @Override
        public boolean ensureCanWrite(BlockPos pos) {
            return true;
        }

        @Override
        public void setCurrentlyGenerating(@Nullable Supplier<String> currentlyGenerating) {
        }

        // Heights.

        @Override
        public int getHeight(Heightmap.Types type, int x, int z) {
            return height(type, x, z);
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public int getMinBuildHeight() {
            return minY;
        }

        @Override
        public int getSeaLevel() {
            return seaLevel;
        }

        // The world.

        @Override
        public long getSeed() {
            return seed;
        }

        @Override
        public Holder<Biome> getBiome(BlockPos pos) {
            return terrain.biome(pos.getX(), pos.getY(), pos.getZ());
        }

        @Override
        public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ) {
            return terrain.biome(QuartPos.toBlock(quartX), QuartPos.toBlock(quartY), QuartPos.toBlock(quartZ));
        }

        @Override
        public Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
            return getNoiseBiome(quartX, quartY, quartZ);
        }

        @Override
        public RegistryAccess registryAccess() {
            return registries;
        }

        @Override
        public FeatureFlagSet enabledFeatures() {
            return FeatureFlags.DEFAULT_FLAGS;
        }

        @Override
        public DimensionType dimensionType() {
            return dimensionType;
        }

        @Override
        public RandomSource getRandom() {
            return RandomSource.create();
        }

        @Override
        public boolean isClientSide() {
            return false;
        }

        @Override
        public int getSkyDarken() {
            return 0;
        }

        @Override
        public long nextSubTickCount() {
            return 0L;
        }

        // Daylight everywhere (crops in village farms check it to survive).

        @Override
        public int getRawBrightness(BlockPos pos, int amount) {
            return Math.max(0, 15 - amount);
        }

        @Override
        public int getMaxLocalRawBrightness(BlockPos pos) {
            return 15;
        }

        @Override
        public int getMaxLocalRawBrightness(BlockPos pos, int amount) {
            return Math.max(0, 15 - amount);
        }

        @Override
        public int getBrightness(LightLayer lightType, BlockPos pos) {
            return 15;
        }

        @Override
        public boolean canSeeSky(BlockPos pos) {
            return true;
        }

        // Chunks.

        @Override
        public ChunkAccess getChunk(int x, int z, ChunkStatus requiredStatus, boolean nonnull) {
            return chunk();
        }

        @Override
        public ChunkAccess getChunk(BlockPos pos) {
            return chunk();
        }

        @Override
        public ChunkAccess getChunk(int chunkX, int chunkZ) {
            return chunk();
        }

        @Override
        public ChunkAccess getChunk(int x, int z, ChunkStatus requiredStatus) {
            return chunk();
        }

        // No entities.

        @Override
        public ServerLevel getLevel() {
            // Entities (a structure's mobs) need a real level. Pieces spawn them after placing their blocks.
            throw new NoEntities();
        }

        @Override
        public List<Entity> getEntities(@Nullable Entity entity, AABB area, Predicate<? super Entity> predicate) {
            return List.of();
        }

        @Override
        public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> entityTypeTest, AABB bounds,
                                                      Predicate<? super T> predicate) {
            return List.of();
        }

        @Override
        public List<Entity> getEntities(@Nullable Entity entity, AABB area) {
            return List.of();
        }

        @Override
        public <T extends Entity> List<T> getEntitiesOfClass(Class<T> entityClass, AABB area, Predicate<? super T> filter) {
            return List.of();
        }

        @Override
        public <T extends Entity> List<T> getEntitiesOfClass(Class<T> entityClass, AABB area) {
            return List.of();
        }

        @Override
        public List<? extends Player> players() {
            return List.of();
        }

        // Ticks, sounds, particles and events: nothing to do.

        @Override
        public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) {
        }

        @Override
        public void scheduleTick(BlockPos pos, Block block, int delay) {
        }

        @Override
        public void scheduleTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) {
        }

        @Override
        public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {
        }

        @Override
        public void blockUpdated(BlockPos pos, Block block) {
        }

        @Override
        public void gameEvent(GameEvent event, Vec3 position, GameEvent.Context context) {
        }

        @Override
        public void gameEvent(@Nullable Entity entity, GameEvent event, Vec3 position) {
        }

        @Override
        public void gameEvent(@Nullable Entity entity, GameEvent event, BlockPos pos) {
        }

        @Override
        public void gameEvent(GameEvent event, BlockPos pos, GameEvent.Context context) {
        }

        @Override
        public void playSound(@Nullable Player player, BlockPos pos, SoundEvent sound, SoundSource source, float volume,
                              float pitch) {
        }

        @Override
        public void playSound(@Nullable Player player, BlockPos pos, SoundEvent sound, SoundSource source) {
        }

        @Override
        public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
        }

        @Override
        public void levelEvent(int type, BlockPos pos, int data) {
        }

        @Override
        public void addParticle(ParticleOptions particleData, double x, double y, double z, double xSpeed, double ySpeed,
                                double zSpeed) {
        }

        // Unsupported: the feature is skipped.

        @Override
        public LevelTickAccess<Block> getBlockTicks() {
            throw unsupported("getBlockTicks");
        }

        @Override
        public LevelTickAccess<Fluid> getFluidTicks() {
            throw unsupported("getFluidTicks");
        }

        @Override
        public LevelData getLevelData() {
            throw unsupported("getLevelData");
        }

        @Override
        public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
            throw unsupported("getCurrentDifficultyAt");
        }

        @Override
        @Nullable
        public MinecraftServer getServer() {
            throw unsupported("getServer");
        }

        @Override
        public ChunkSource getChunkSource() {
            throw unsupported("getChunkSource");
        }

        @Override
        public long dayTime() {
            throw unsupported("dayTime");
        }

        @Override
        public boolean hasChunk(int chunkX, int chunkZ) {
            throw unsupported("hasChunk");
        }

        @Override
        public BiomeManager getBiomeManager() {
            throw unsupported("getBiomeManager");
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            throw unsupported("getShade");
        }

        @Override
        public LevelLightEngine getLightEngine() {
            throw unsupported("getLightEngine");
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            throw unsupported("getBlockTint");
        }

        @Override
        public WorldBorder getWorldBorder() {
            throw unsupported("getWorldBorder");
        }

        @Override
        @Nullable
        public BlockGetter getChunkForCollisions(int chunkX, int chunkZ) {
            throw unsupported("getChunkForCollisions");
        }

        @Override
        public List<VoxelShape> getEntityCollisions(@Nullable Entity entity, AABB collisionBox) {
            throw unsupported("getEntityCollisions");
        }

        @Override
        public String toString() {
            return "VirtualLevel";
        }
    }
}
