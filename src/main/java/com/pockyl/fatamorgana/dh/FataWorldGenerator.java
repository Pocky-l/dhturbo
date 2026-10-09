package com.pockyl.fatamorgana.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.neoforged.fml.loading.FMLEnvironment;

import com.pockyl.fatamorgana.Config;
import com.pockyl.fatamorgana.Fatamorgana;
import com.pockyl.fatamorgana.surface.BiomeLook;
import com.pockyl.fatamorgana.surface.FakeTrees;
import com.pockyl.fatamorgana.surface.RealTrees;
import com.pockyl.fatamorgana.surface.SurfaceDetail;
import com.pockyl.fatamorgana.surface.SurfaceSampler;
import com.pockyl.fatamorgana.surface.SurfaceTile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Distant Horizons world generator that builds LODs straight from the terrain noise: surface, water, snow, ice and
 * fake trees, no chunks. Every detail level costs about the same, so the far horizon fills as fast as the near one.
 */
final class FataWorldGenerator implements IDhApiWorldGenerator {
    /** Detail levels DH may ask for: 0 = one block per column, 12 = 4096 blocks per column. */
    private static final byte MAX_DETAIL = 12;
    private static final int MAX_LIGHT = 15;
    /**
     * Generation step recorded for every column. DH re-requests a tile while its columns are below the step its
     * generator plan requires, which is {@code FEATURES} for full-detail tiles under the default
     * {@code SURFACE_THEN_CHUNKS} plan: marking our columns {@code SURFACE} made DH regenerate the tiles around the
     * player forever. Real chunks ({@code LIGHT}) still replace this data when the player loads them.
     */
    private static final EDhApiWorldGenerationStep STEP = EDhApiWorldGenerationStep.FEATURES;

    /** Columns at most this wide get the game's own trees (when enabled); wider ones approximate trees. */
    private static final int REAL_TREES_MAX_SPACING = 2;

    private final IDhApiLevelWrapper levelWrapper;
    private final ServerLevel level;
    private final SurfaceSampler sampler;
    private final RealTrees realTrees;
    private final BlockState defaultBlock;
    private final long seed;
    private final boolean fullResolution;
    private final int fullResolutionRadius;
    private final boolean fakeTrees;
    private final Map<BlockState, IDhApiBlockStateWrapper> blockWrappers = new ConcurrentHashMap<>();
    private final Map<Holder<Biome>, IDhApiBiomeWrapper> biomeWrappers = new ConcurrentHashMap<>();
    private final GeneratorStats stats;
    private final AtomicInteger loggedErrors = new AtomicInteger();

    FataWorldGenerator(IDhApiLevelWrapper levelWrapper, ServerLevel level, NoiseBasedChunkGenerator generator) {
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        this.levelWrapper = levelWrapper;
        this.level = level;
        this.sampler = new SurfaceSampler(level.registryAccess(), settings, generator.getBiomeSource(), level.getSeed());
        this.fakeTrees = Config.FAKE_TREES.get();
        this.realTrees = fakeTrees && Config.REAL_TREES.get()
                ? new RealTrees(generator, sampler, level.getSeed(), level.registryAccess(), level.dimensionType())
                : null;
        this.defaultBlock = settings.defaultBlock();
        this.seed = level.getSeed();
        this.fullResolution = Config.FULL_RESOLUTION.get();
        this.fullResolutionRadius = Config.FULL_RESOLUTION_RADIUS.get();
        this.stats = new GeneratorStats(level, levelWrapper.getDimensionName());
    }

    @Override
    public byte getSmallestDataDetailLevel() {
        return 0;
    }

    @Override
    public byte getLargestDataDetailLevel() {
        return MAX_DETAIL;
    }

    @Override
    public EDhApiWorldGeneratorReturnType getReturnType() {
        return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
    }

    @Override
    public boolean runApiValidation() {
        return !FMLEnvironment.production;
    }

    @Override
    public CompletableFuture<Void> generateLod(int chunkPosMinX, int chunkPosMinZ, int lodPosX, int lodPosZ,
                                              byte detailLevel, IDhApiFullDataSource dataSource,
                                              EDhApiDistantGeneratorMode generatorMode, ExecutorService dhThreadPool,
                                              Consumer<IDhApiFullDataSource> resultConsumer) {
        int minX = chunkPosMinX * 16;
        int minZ = chunkPosMinZ * 16;
        int spacing = 1 << detailLevel;
        int halfWidth = dataSource.getWidthInDataColumns() * spacing / 2;
        stats.request(detailLevel, lodPosX, lodPosZ, minX + halfWidth, minZ + halfWidth);
        return CompletableFuture.runAsync(() -> {
            try {
                fill(minX, minZ, spacing, dataSource);
            } catch (RuntimeException e) {
                // DH swallows failed futures silently; without this a broken tile is just a hole in the horizon.
                stats.error();
                if (loggedErrors.incrementAndGet() <= 10) {
                    Fatamorgana.LOGGER.error("Failed to generate LOD tile at block {} {}, detail {}", minX, minZ, detailLevel, e);
                }
                throw e;
            }
            resultConsumer.accept(dataSource);
        }, WorkerPool.get());
    }

    private void fill(int minX, int minZ, int spacing, IDhApiFullDataSource dataSource) {
        long start = System.nanoTime();
        int width = dataSource.getWidthInDataColumns();
        int half = width * spacing / 2;
        boolean near = fullResolution || Players.nearestDistance(level, minX + half, minZ + half) <= fullResolutionRadius;
        boolean real = realTrees != null && spacing <= REAL_TREES_MAX_SPACING;
        // A margin of columns around the tile: slopes at the edges, and trees of neighbouring chunks.
        int margin = real ? RealTrees.MARGIN / spacing + 1 : 1;
        SurfaceTile area = SurfaceTile.generate(sampler, minX - margin * spacing, minZ - margin * spacing, spacing,
                width + 2 * margin, near ? 1 : 2);
        RealTrees.TileTrees trees = real ? realTrees.plant(area, minX, minZ, width) : null;
        if (real) {
            stats.treeFailures(realTrees.takeFailures());
        }

        List<DhApiTerrainDataPoint> points = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < width; j++) {
                points.clear();
                int x = minX + i * spacing;
                int z = minZ + j * spacing;
                FakeTrees.Canopy canopy = null;
                BlockState treeTop = null;
                if (trees != null) {
                    canopy = trees.canopies()[i + j * width];
                    treeTop = trees.tops()[i + j * width];
                } else if (fakeTrees) {
                    canopy = FakeTrees.at(seed, x, z, spacing, BiomeLook.of(area.biome(i + margin, j + margin)));
                }
                column(points, x, z, area, i + margin, j + margin, canopy, treeTop);
                dataSource.setApiDataPointColumn(i, j, STEP, points);
            }
        }
        stats.done(System.nanoTime() - start);
    }

    /**
     * Builds a column bottom to top; heights in data points are relative to the bottom of the level.
     *
     * @param canopy  tree over the column, or null
     * @param treeTop top block the trees replaced (podzol under giant spruces), or null
     */
    private void column(List<DhApiTerrainDataPoint> points, int x, int z, SurfaceTile area, int i, int j,
                        FakeTrees.Canopy canopy, BlockState treeTop) {
        int ground = area.height(i, j);
        Holder<Biome> biome = area.biome(i, j);
        int minY = sampler.minY();
        int top = sampler.maxY() - minY;
        int seaLevel = sampler.seaLevel();
        IDhApiBiomeWrapper biomeWrapper = biomeWrapper(biome);
        BiomeLook look = BiomeLook.of(biome);
        boolean overworld = biome.is(BiomeTags.IS_OVERWORLD);
        int groundRel = Math.max(ground - minY, 0);

        if (ground < seaLevel) {
            int seaRel = seaLevel - minY;
            int depth = seaLevel - ground;
            if (groundRel > 0) {
                BlockState floor = overworld ? look.underwater() : defaultBlock;
                points.add(point(floor, Math.max(0, MAX_LIGHT - depth), 0, groundRel, biomeWrapper));
            }
            boolean frozen = biome.value().coldEnoughToSnow(new BlockPos(x, seaLevel, z));
            if (frozen && depth >= 1) {
                if (depth > 1) {
                    points.add(point(Blocks.WATER.defaultBlockState(), MAX_LIGHT - 1, groundRel, seaRel - 1, biomeWrapper));
                }
                points.add(point(Blocks.ICE.defaultBlockState(), MAX_LIGHT, seaRel - 1, seaRel, biomeWrapper));
            } else {
                points.add(point(Blocks.WATER.defaultBlockState(), MAX_LIGHT, groundRel, seaRel, biomeWrapper));
            }
            points.add(point(Blocks.AIR.defaultBlockState(), MAX_LIGHT, seaRel, top, biomeWrapper));
            return;
        }

        BlockState surface;
        if (!overworld) {
            surface = defaultBlock;
        } else if (treeTop != null) {
            surface = treeTop;
        } else {
            surface = SurfaceDetail.top(biome, look, seed, x, ground, z, area.slope(i, j));
        }
        if (groundRel > 0) {
            points.add(point(surface, MAX_LIGHT, 0, groundRel, biomeWrapper));
        }
        int airFrom = groundRel;
        if (canopy != null && overworld) {
            int leavesBottom = Math.min(top, groundRel + Math.max(0, canopy.leavesBottom()));
            int leavesTop = Math.min(top, groundRel + canopy.leavesTop());
            if (leavesBottom > groundRel) {
                BlockState under = canopy.trunk() ? canopy.log() : Blocks.AIR.defaultBlockState();
                points.add(point(under, MAX_LIGHT, groundRel, leavesBottom, biomeWrapper));
            }
            if (leavesTop > leavesBottom) {
                points.add(point(canopy.leaves(), MAX_LIGHT, leavesBottom, leavesTop, biomeWrapper));
            }
            airFrom = Math.max(groundRel, Math.max(leavesBottom, leavesTop));
        }
        if (airFrom < top) {
            points.add(point(Blocks.AIR.defaultBlockState(), MAX_LIGHT, airFrom, top, biomeWrapper));
        }
    }

    private DhApiTerrainDataPoint point(BlockState state, int skyLight, int bottom, int top, IDhApiBiomeWrapper biome) {
        return DhApiTerrainDataPoint.create((byte) 0, state.getLightEmission(), skyLight, bottom, top,
                blockWrapper(state), biome);
    }

    private IDhApiBlockStateWrapper blockWrapper(BlockState state) {
        return blockWrappers.computeIfAbsent(state, key -> state.isAir()
                ? DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper()
                : DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[]{key}, levelWrapper));
    }

    private IDhApiBiomeWrapper biomeWrapper(Holder<Biome> biome) {
        return biomeWrappers.computeIfAbsent(biome,
                key -> DhApi.Delayed.wrapperFactory.getBiomeWrapper(new Object[]{key}, levelWrapper));
    }

    @Override
    public void preGeneratorTaskStart() {
    }

    @Override
    public void close() {
    }
}
