package com.pockyl.dhturbo.dh;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.generation.DhWorldGenerator;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.fml.loading.FMLEnvironment;

import com.pockyl.dhturbo.DhTurbo;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Distant Horizons world generator that builds LODs straight from the terrain noise: surface, water, snow, ice and
 * fake trees, no chunks. Every detail level costs about the same, so the far horizon fills as fast as the near one.
 */
final class TurboWorldGenerator implements IDhApiWorldGenerator {
    /** Detail levels DH may ask for: 0 = one block per column, 12 = 4096 blocks per column. */
    private static final byte MAX_DETAIL = 12;
    private static final int MAX_LIGHT = 15;
    private final IDhApiLevelWrapper levelWrapper;
    private final LodTileWriter writer;
    /** Request statistics in the log, developer runs only. */
    private final GeneratorStats stats;
    private final AtomicInteger loggedErrors = new AtomicInteger();
    /** DH's own generator, which takes over while DH Turbo is switched off. Created on first use. */
    private volatile IDhApiWorldGenerator dhGenerator;
    private volatile boolean dhGeneratorUnavailable;

    TurboWorldGenerator(IDhApiLevelWrapper levelWrapper, ServerLevel level, NoiseBasedChunkGenerator generator) {
        this.levelWrapper = levelWrapper;
        this.stats = DhTurbo.DEBUG ? new GeneratorStats(level, levelWrapper.getDimensionName()) : null;
        this.writer = new LodTileWriter(levelWrapper, generator, level.registryAccess(), level.dimensionType(),
                level.getSeed(), UnaryOperator.identity(), stats);
    }

    /** The generator answering requests right now: DH's own one while DH Turbo is switched off. */
    private IDhApiWorldGenerator delegate() {
        if (DhIntegration.active() || dhGeneratorUnavailable) {
            return null;
        }
        IDhApiWorldGenerator generator = dhGenerator;
        if (generator == null) {
            synchronized (this) {
                generator = dhGenerator;
                if (generator == null) {
                    try {
                        // DH internals: its generator needs the DH level, which exists once the level finished loading.
                        IDhLevel dhLevel = SharedApi.getAbstractDhWorld().getLevel((ILevelWrapper) levelWrapper);
                        generator = new DhWorldGenerator((IDhServerLevel) dhLevel);
                        dhGenerator = generator;
                    } catch (RuntimeException | LinkageError e) {
                        dhGeneratorUnavailable = true;
                        DhTurbo.LOGGER.error("Cannot hand generation back to Distant Horizons; DH Turbo keeps generating", e);
                        return null;
                    }
                }
            }
        }
        return generator;
    }

    @Override
    public byte getSmallestDataDetailLevel() {
        IDhApiWorldGenerator delegate = delegate();
        return delegate != null ? delegate.getSmallestDataDetailLevel() : 0;
    }

    @Override
    public byte getLargestDataDetailLevel() {
        IDhApiWorldGenerator delegate = delegate();
        return delegate != null ? delegate.getLargestDataDetailLevel() : MAX_DETAIL;
    }

    @Override
    public EDhApiWorldGeneratorReturnType getReturnType() {
        IDhApiWorldGenerator delegate = delegate();
        return delegate != null ? delegate.getReturnType() : EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
    }

    @Override
    public boolean runApiValidation() {
        IDhApiWorldGenerator delegate = delegate();
        return delegate != null ? delegate.runApiValidation() : !FMLEnvironment.production;
    }

    @Override
    public CompletableFuture<Void> generateLod(int chunkPosMinX, int chunkPosMinZ, int lodPosX, int lodPosZ,
                                              byte detailLevel, IDhApiFullDataSource dataSource,
                                              EDhApiDistantGeneratorMode generatorMode, ExecutorService dhThreadPool,
                                              Consumer<IDhApiFullDataSource> resultConsumer) {
        IDhApiWorldGenerator delegate = delegate();
        if (delegate != null) {
            return delegate.generateLod(chunkPosMinX, chunkPosMinZ, lodPosX, lodPosZ, detailLevel, dataSource,
                    generatorMode, dhThreadPool, resultConsumer);
        }
        int minX = chunkPosMinX * 16;
        int minZ = chunkPosMinZ * 16;
        int spacing = 1 << detailLevel;
        int halfWidth = dataSource.getWidthInDataColumns() * spacing / 2;
        if (stats != null) {
            stats.request(detailLevel, lodPosX, lodPosZ, minX + halfWidth, minZ + halfWidth);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                writer.fill(minX, minZ, spacing, dataSource);
            } catch (RuntimeException e) {
                // DH swallows failed futures silently; without this a broken tile is just a hole in the horizon.
                if (stats != null) {
                    stats.error();
                }
                if (loggedErrors.incrementAndGet() <= 10) {
                    DhTurbo.LOGGER.error("Failed to generate LOD tile at block {} {}, detail {}", minX, minZ, detailLevel, e);
                }
                throw e;
            }
            resultConsumer.accept(dataSource);
        }, WorkerPool.get());
    }

    @Override
    public void preGeneratorTaskStart() {
        IDhApiWorldGenerator generator = dhGenerator;
        if (generator != null) {
            generator.preGeneratorTaskStart();
        }
    }

    @Override
    public void close() {
        IDhApiWorldGenerator generator = dhGenerator;
        if (generator != null) {
            generator.close();
        }
    }
}
