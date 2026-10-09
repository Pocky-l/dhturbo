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
import com.pockyl.fatamorgana.surface.SurfaceSampler;
import com.pockyl.fatamorgana.surface.SurfaceTile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Distant Horizons world generator that builds LODs straight from the terrain noise: surface, water, snow, ice and
 * fake trees, no chunks. Every detail level costs the same, so the far horizon fills as fast as the near one.
 */
final class FataWorldGenerator implements IDhApiWorldGenerator {
    /** Detail levels DH may ask for: 0 = one block per column, 12 = 4096 blocks per column. */
    private static final byte MAX_DETAIL = 12;
    private static final int MAX_LIGHT = 15;
    private static final int STATS_EVERY = 500;

    private final IDhApiLevelWrapper levelWrapper;
    private final SurfaceSampler sampler;
    private final BlockState defaultBlock;
    private final long seed;
    private final int stride;
    private final boolean fakeTrees;
    private final Map<BlockState, IDhApiBlockStateWrapper> blockWrappers = new ConcurrentHashMap<>();
    private final Map<Holder<Biome>, IDhApiBiomeWrapper> biomeWrappers = new ConcurrentHashMap<>();
    private final AtomicLong tiles = new AtomicLong();
    private final AtomicLong surfaceNanos = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();

    FataWorldGenerator(IDhApiLevelWrapper levelWrapper, ServerLevel level, NoiseBasedChunkGenerator generator) {
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        this.levelWrapper = levelWrapper;
        this.sampler = new SurfaceSampler(level.registryAccess(), settings, generator.getBiomeSource(), level.getSeed());
        this.defaultBlock = settings.defaultBlock();
        this.seed = level.getSeed();
        this.stride = Config.FULL_RESOLUTION.get() ? 1 : 2;
        this.fakeTrees = Config.FAKE_TREES.get();
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
        return CompletableFuture.runAsync(() -> {
            fill(chunkPosMinX * 16, chunkPosMinZ * 16, 1 << detailLevel, dataSource);
            resultConsumer.accept(dataSource);
        }, WorkerPool.get());
    }

    private void fill(int minX, int minZ, int spacing, IDhApiFullDataSource dataSource) {
        long start = System.nanoTime();
        int width = dataSource.getWidthInDataColumns();
        SurfaceTile tile = SurfaceTile.generate(sampler, minX, minZ, spacing, width, stride);
        long surfaceDone = System.nanoTime();

        List<DhApiTerrainDataPoint> points = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < width; j++) {
                points.clear();
                column(points, minX + i * spacing, minZ + j * spacing, spacing, tile.height(i, j), tile.biome(i, j));
                dataSource.setApiDataPointColumn(i, j, EDhApiWorldGenerationStep.SURFACE, points);
            }
        }
        record(start, surfaceDone, System.nanoTime());
    }

    /** Builds a column bottom to top; heights in data points are relative to the bottom of the level. */
    private void column(List<DhApiTerrainDataPoint> points, int x, int z, int spacing, int ground, Holder<Biome> biome) {
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

        BlockState surface = overworld ? look.top() : defaultBlock;
        if (overworld && biome.value().coldEnoughToSnow(new BlockPos(x, ground, z))) {
            surface = Blocks.SNOW_BLOCK.defaultBlockState();
        }
        if (groundRel > 0) {
            points.add(point(surface, MAX_LIGHT, 0, groundRel, biomeWrapper));
        }
        int airFrom = groundRel;
        FakeTrees.Canopy canopy = fakeTrees && overworld ? FakeTrees.at(seed, x, z, spacing, look) : null;
        if (canopy != null) {
            int leavesBottom = Math.min(top, groundRel + canopy.leavesBottom());
            int leavesTop = Math.min(top, groundRel + canopy.leavesTop());
            if (leavesBottom > groundRel) {
                BlockState under = canopy.trunk() ? canopy.tree().log() : Blocks.AIR.defaultBlockState();
                points.add(point(under, MAX_LIGHT, groundRel, leavesBottom, biomeWrapper));
            }
            if (leavesTop > leavesBottom) {
                points.add(point(canopy.tree().leaves(), MAX_LIGHT, leavesBottom, leavesTop, biomeWrapper));
            }
            airFrom = leavesTop;
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

    private void record(long start, long surfaceDone, long end) {
        long surface = surfaceNanos.addAndGet(surfaceDone - start);
        long total = totalNanos.addAndGet(end - start);
        long count = tiles.incrementAndGet();
        if (count % STATS_EVERY == 0) {
            Fatamorgana.LOGGER.info("{} LOD tiles generated for {}: {} ms per tile on average ({} ms surface search)",
                    count, levelWrapper.getDimensionName(), String.format("%.1f", total / 1e6 / count),
                    String.format("%.1f", surface / 1e6 / count));
        }
    }

    @Override
    public void preGeneratorTaskStart() {
    }

    @Override
    public void close() {
    }
}
