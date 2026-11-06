package com.pockyl.dhturbo.surface;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.ticks.ProtoChunkTicks;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.surface.BuildProfile.Stage;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The real world generator on chunks that never enter the game: biomes, noise, surface, carvers and every feature and
 * structure run as in chunk generation, on proto chunks of our own, without the chunk map, lighting, entities or
 * saving. Tiles built from them equal the real world block for block: caves, aquifers, ores on cliffs, every tree.
 * <p>
 * Decorating a chunk needs its neighbours up to the carver step, so chunks are generated in batches of
 * {@value #BATCH} x {@value #BATCH} that share that border: {@code ((B + 4) / B)^2} proto chunks per chunk.
 */
public final class ExactChunks {
    /** Chunks per side of a batch: 8 costs 2.25 proto chunks per chunk, 4 would cost 4. */
    public static final int BATCH = 8;
    /** Proto chunks around a batch: its features reach one chunk out, and those centres need their neighbours. */
    private static final int HALO = 2;
    /** Reach of structures and carvers in chunks. */
    private static final int REACH = 8;
    private static final int GRID = BATCH + 2 * HALO;
    private static final int MAX_LIGHT = 15;
    /** Finished batches kept: a batch serves four full-detail tiles. */
    private static final int CACHED_BATCHES = 16;
    private static final int CACHED_STUBS = 8192;

    private final ServerLevel level;
    private final NoiseBasedChunkGenerator generator;
    private final RandomState random;
    private final Registry<Biome> biomes;
    private final LevelChunkSection[] noSections;
    private final boolean structures;
    private final long seed;
    private final int minY;
    private final int levelTop;
    private final int minCellY;
    private final int cellCountY;
    private final ChunkStep surfaceStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.SURFACE);
    private final ChunkStep featureStep = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);
    /** Chunks outside batches: they only answer for their structure starts and their carver biome. */
    private final Cache<Long, ProtoChunk> stubs = CacheBuilder.newBuilder().maximumSize(CACHED_STUBS).build();
    private final Cache<Long, CompletableFuture<Batch>> batches =
            CacheBuilder.newBuilder().maximumSize(CACHED_BATCHES).build();
    private final Map<BlockState, Integer> lightBlock = new ConcurrentHashMap<>();
    private final AtomicBoolean reported = new AtomicBoolean();
    private final boolean reverseOrder;

    public ExactChunks(ServerLevel level, NoiseBasedChunkGenerator generator) {
        this(level, generator, false);
    }

    /** @param reverseOrder decorate chunks in the opposite order, to measure how much the order changes the result */
    public ExactChunks(ServerLevel level, NoiseBasedChunkGenerator generator, boolean reverseOrder) {
        this.reverseOrder = reverseOrder;
        this.level = level;
        this.generator = generator;
        this.random = level.getChunkSource().randomState();
        this.biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        this.noSections = new ProtoChunk(ChunkPos.ZERO, UpgradeData.EMPTY, level, biomes, null).getSections();
        this.structures = level.getServer().getWorldData().worldGenOptions().generateStructures();
        this.seed = level.getSeed();
        this.minY = level.getMinBuildHeight();
        this.levelTop = level.getHeight();
        NoiseSettings noise = generator.generatorSettings().value().noiseSettings().clampToHeightAccessor(level);
        this.minCellY = Mth.floorDiv(noise.minY(), noise.getCellHeight());
        this.cellCountY = Mth.floorDiv(noise.height(), noise.getCellHeight());
    }

    /**
     * Hands the columns of a tile to the sink. Returns false when they cannot be generated this way (then nothing was
     * handed over); the reason is logged once.
     */
    public boolean tile(int minX, int minZ, int spacing, int width, UnaryOperator<Holder<Biome>> levelBiome,
                        ColumnBuilder.Sink sink) {
        try {
            int size = width * spacing;
            int fromBatchX = Math.floorDiv(minX >> 4, BATCH);
            int fromBatchZ = Math.floorDiv(minZ >> 4, BATCH);
            int toBatchX = Math.floorDiv((minX + size - 1) >> 4, BATCH);
            int toBatchZ = Math.floorDiv((minZ + size - 1) >> 4, BATCH);
            List<Batch> needed = new ArrayList<>();
            for (int bz = fromBatchZ; bz <= toBatchZ; bz++) {
                for (int bx = fromBatchX; bx <= toBatchX; bx++) {
                    needed.add(batch(bx, bz));
                }
            }
            int across = toBatchX - fromBatchX + 1;
            for (int i = 0; i < width; i++) {
                for (int j = 0; j < width; j++) {
                    int x = minX + i * spacing;
                    int z = minZ + j * spacing;
                    Batch batch = needed.get(Math.floorDiv(x >> 4, BATCH) - fromBatchX
                            + (Math.floorDiv(z >> 4, BATCH) - fromBatchZ) * across);
                    int index = batch.index(x, z);
                    sink.column(i, j, levelBiome.apply(batch.biomes[index]), batch.columns[index]);
                }
            }
            return true;
        } catch (RuntimeException | ExecutionException | LinkageError e) {
            if (reported.compareAndSet(false, true)) {
                DhTurbo.LOGGER.warn("Real generation of distant tiles failed; those tiles use the fast generator", e);
            }
            return false;
        }
    }

    private Batch batch(int batchX, int batchZ) throws ExecutionException {
        CompletableFuture<Batch> mine = new CompletableFuture<>();
        CompletableFuture<Batch> shared = batches.get(ChunkPos.asLong(batchX, batchZ), () -> mine);
        if (shared == mine) {
            try {
                mine.complete(generate(batchX * BATCH, batchZ * BATCH));
            } catch (RuntimeException | Error e) {
                batches.invalidate(ChunkPos.asLong(batchX, batchZ));
                mine.completeExceptionally(e);
                throw e;
            }
        }
        return shared.join();
    }

    /** Generates a batch and hands over its finished chunks, on the calling pool's threads. */
    public void generate(int batchX, int batchZ, Consumer<ChunkAccess> consumer) {
        int originX = batchX * BATCH;
        int originZ = batchZ * BATCH;
        Grid chunks = chunks(originX, originZ);
        for (int z = 0; z < BATCH; z++) {
            for (int x = 0; x < BATCH; x++) {
                consumer.accept(chunks.at(originX + x, originZ + z));
                BuildProfile.exactChunk();
            }
        }
    }

    private Batch generate(int originX, int originZ) {
        Grid chunks = chunks(originX, originZ);
        Batch batch = new Batch(originX, originZ);
        List<Runnable> columns = new ArrayList<>();
        for (int z = 0; z < BATCH; z++) {
            for (int x = 0; x < BATCH; x++) {
                int cx = x;
                int cz = z;
                columns.add(() -> {
                    long start = System.nanoTime();
                    extract(batch, chunks, originX + cx, originZ + cz);
                    BuildProfile.lap(Stage.EXACT_COLUMNS, start);
                    BuildProfile.exactChunk();
                });
            }
        }
        SurfaceTile.run(columns);
        return batch;
    }

    private Grid chunks(int originX, int originZ) {
        ChunkAccess[] grid = new ChunkAccess[GRID * GRID];
        Grid chunks = new Grid(originX - HALO, originZ - HALO, grid);
        // Structure starts of everything that can reach the grid, in parallel: villages are slow.
        long begin = System.nanoTime();
        List<Runnable> rows = new ArrayList<>();
        for (int z = originZ - HALO - REACH; z < originZ + BATCH + HALO + REACH; z++) {
            int row = z;
            rows.add(() -> {
                for (int x = originX - HALO - REACH; x < originX + BATCH + HALO + REACH; x++) {
                    stub(x, row);
                }
            });
        }
        SurfaceTile.run(rows);
        BuildProfile.lap(Stage.EXACT_STARTS, begin);
        // Every chunk knows its starts first: references and biomes read their neighbours.
        for (int n = 0; n < grid.length; n++) {
            ProtoChunk chunk = new ProtoChunk(new ChunkPos(chunks.originX + n % GRID, chunks.originZ + n / GRID),
                    UpgradeData.EMPTY, level, biomes, null);
            stub(chunk.getPos().x, chunk.getPos().z).getAllStarts().forEach(chunk::setStartForStructure);
            grid[n] = chunk;
        }
        List<Runnable> biomeTasks = new ArrayList<>();
        List<Runnable> terrainTasks = new ArrayList<>();
        for (ChunkAccess chunk : grid) {
            biomeTasks.add(() -> biomes(chunks, (ProtoChunk) chunk));
            terrainTasks.add(() -> terrain(chunks, (ProtoChunk) chunk));
        }
        // Surface rules read the biomes of neighbouring chunks.
        SurfaceTile.run(biomeTasks);
        SurfaceTile.run(terrainTasks);
        // Neighbouring centres touch the same chunks, so centres three chunks apart run together.
        for (int step = 0; step < 9; step++) {
            int colour = reverseOrder ? 8 - step : step;
            List<Runnable> centres = new ArrayList<>();
            for (int z = originZ - 1; z <= originZ + BATCH; z++) {
                for (int x = originX - 1; x <= originX + BATCH; x++) {
                    if (Math.floorMod(x, 3) + 3 * Math.floorMod(z, 3) == colour) {
                        ChunkAccess centre = chunks.at(x, z);
                        centres.add(() -> decorate(chunks, centre));
                    }
                }
            }
            SurfaceTile.run(centres);
        }
        return chunks;
    }

    /** Structure references and biomes of a chunk. */
    private void biomes(Grid chunks, ProtoChunk chunk) {
        long begin = System.nanoTime();
        Region region = new Region(level, surfaceStep, chunk, chunks);
        StructureManager manager = level.structureManager().forWorldGenRegion(region);
        if (structures) {
            generator.createReferences(region, manager, chunk);
        }
        generator.doCreateBiomes(Blender.empty(), random, manager, chunk);
        chunk.setPersistedStatus(ChunkStatus.BIOMES);
        BuildProfile.lap(Stage.EXACT_BIOMES, begin);
    }

    /** A chunk from its biomes up to the carver step. */
    private void terrain(Grid chunks, ProtoChunk chunk) {
        long begin = System.nanoTime();
        Region region = new Region(level, surfaceStep, chunk, chunks);
        StructureManager manager = level.structureManager().forWorldGenRegion(region);
        generator.doFill(Blender.empty(), manager, random, chunk, minCellY, cellCountY);
        chunk.setPersistedStatus(ChunkStatus.NOISE);
        begin = BuildProfile.lap(Stage.EXACT_NOISE, begin);
        generator.buildSurface(chunk, new WorldGenerationContext(generator, region), random, manager,
                region.getBiomeManager(), biomes, Blender.empty());
        chunk.setPersistedStatus(ChunkStatus.SURFACE);
        begin = BuildProfile.lap(Stage.EXACT_SURFACE, begin);
        generator.applyCarvers(region, seed, random, level.getBiomeManager(), manager, chunk, GenerationStep.Carving.AIR);
        chunk.setPersistedStatus(ChunkStatus.CARVERS);
        BuildProfile.lap(Stage.EXACT_CARVERS, begin);
    }

    private void decorate(Grid chunks, ChunkAccess chunk) {
        long begin = System.nanoTime();
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE));
        Region region = new Region(level, featureStep, chunk, chunks);
        generator.applyBiomeDecoration(region, chunk, level.structureManager().forWorldGenRegion(region));
        ((ProtoChunk) chunk).setPersistedStatus(ChunkStatus.FEATURES);
        BuildProfile.lap(Stage.EXACT_FEATURES, begin);
    }

    /** A chunk holding nothing but its structure starts. */
    private ProtoChunk stub(int x, int z) {
        try {
            return stubs.get(ChunkPos.asLong(x, z), () -> {
                ProtoChunk stub = new ProtoChunk(new ChunkPos(x, z), UpgradeData.EMPTY, noSections,
                        new ProtoChunkTicks<>(), new ProtoChunkTicks<>(), level, biomes, null);
                if (structures) {
                    generator.createStructures(level.registryAccess(), level.getChunkSource().getGeneratorState(),
                            level.structureManager(), stub, level.getStructureManager());
                }
                return stub;
            });
        } catch (ExecutionException e) {
            throw new IllegalStateException("Structure starts of chunk " + x + " " + z, e);
        }
    }

    /** Reads the finished columns of a chunk into runs of one block, bottom to top. */
    private void extract(Batch batch, Grid chunks, int chunkX, int chunkZ) {
        ChunkAccess chunk = chunks.at(chunkX, chunkZ);
        LevelChunkSection[] sections = chunk.getSections();
        BiomeManager zoom = new BiomeManager((x, y, z) -> {
            ChunkAccess source = chunks.at(x >> 2, z >> 2);
            return source != null ? source.getNoiseBiome(x, y, z)
                    : generator.getBiomeSource().getNoiseBiome(x, y, z, random.sampler());
        }, BiomeManager.obfuscateSeed(seed));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState[] states = new BlockState[levelTop + 1];
        int[] starts = new int[levelTop + 2];
        BlockState air = Blocks.AIR.defaultBlockState();
        int blockX = chunk.getPos().getMinBlockX();
        int blockZ = chunk.getPos().getMinBlockZ();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int runs = 0;
                BlockState last = null;
                int y = 0;
                int surface = 0;
                for (LevelChunkSection section : sections) {
                    if (section.hasOnlyAir()) {
                        if (last != air) {
                            states[runs] = air;
                            starts[runs++] = y;
                            last = air;
                        }
                        y += 16;
                        continue;
                    }
                    for (int ly = 0; ly < 16; ly++, y++) {
                        BlockState state = section.getBlockState(lx, ly, lz);
                        if (state != last) {
                            states[runs] = state;
                            starts[runs++] = y;
                            last = state;
                        }
                        if (!state.isAir()) {
                            surface = y;
                        }
                    }
                }
                starts[runs] = levelTop;
                ColumnBuilder.Layer[] layers = new ColumnBuilder.Layer[runs];
                // Sky light from the top: every run dims it like the game's vertical light does.
                int sky = level.dimensionType().hasSkyLight() ? MAX_LIGHT : 0;
                for (int run = runs - 1; run >= 0; run--) {
                    BlockState state = states[run];
                    layers[run] = new ColumnBuilder.Layer(state, sky, starts[run], starts[run + 1]);
                    int dims = lightBlock.computeIfAbsent(state,
                            key -> key.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
                    sky = Math.max(0, sky - dims * (starts[run + 1] - starts[run]));
                }
                int index = batch.index(blockX + lx, blockZ + lz);
                batch.columns[index] = List.of(layers);
                batch.biomes[index] = zoom.getBiome(pos.set(blockX + lx, surface + minY, blockZ + lz));
            }
        }
    }

    /** The chunks of a batch with their border. */
    private static final class Grid {
        final int originX;
        final int originZ;
        final ChunkAccess[] chunks;

        Grid(int originX, int originZ, ChunkAccess[] chunks) {
            this.originX = originX;
            this.originZ = originZ;
            this.chunks = chunks;
        }

        @Nullable
        ChunkAccess at(int x, int z) {
            int i = x - originX;
            int j = z - originZ;
            return i < 0 || j < 0 || i >= GRID || j >= GRID ? null : chunks[i + j * GRID];
        }
    }

    /** Finished columns of a batch. */
    private static final class Batch {
        private static final int WIDTH = BATCH * 16;
        final int minX;
        final int minZ;
        @SuppressWarnings("unchecked")
        final List<ColumnBuilder.Layer>[] columns = (List<ColumnBuilder.Layer>[]) new List[WIDTH * WIDTH];
        @SuppressWarnings("unchecked")
        final Holder<Biome>[] biomes = (Holder<Biome>[]) new Holder[WIDTH * WIDTH];

        Batch(int originChunkX, int originChunkZ) {
            this.minX = originChunkX * 16;
            this.minZ = originChunkZ * 16;
        }

        int index(int x, int z) {
            return x - minX + (z - minZ) * WIDTH;
        }
    }

    /** A vanilla region over the batch's chunks. */
    private final class Region extends WorldGenRegion {
        private final Grid chunks;

        Region(ServerLevel level, ChunkStep step, ChunkAccess center, Grid chunks) {
            super(level, StaticCache2D.create(center.getPos().x, center.getPos().z, 0, (x, z) -> null), step, center);
            this.chunks = chunks;
        }

        @Override
        public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean require) {
            ChunkAccess chunk = chunks.at(x, z);
            if (chunk != null) {
                return chunk;
            }
            return require ? stub(x, z) : null;
        }

        @Override
        public boolean hasChunk(int x, int z) {
            return chunks.at(x, z) != null;
        }
    }
}
