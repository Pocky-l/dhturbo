package com.pockyl.dhturbo.dh;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.generation.DhWorldGenerator;
import com.seibel.distanthorizons.core.level.AbstractDhServerLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraftforge.fml.loading.FMLEnvironment;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
    /**
     * Full-detail tiles this generator filled since the level loaded, for {@code real_chunks_nearby}: when DH asks for
     * one again, it wants real chunks. Tiles from earlier sessions are recognised by their generation step.
     */
    private final Set<Long> fastDetailTiles = ConcurrentHashMap.newKeySet();
    private static final int FAST_TILES_KEPT = 65_536;

    TurboWorldGenerator(IDhApiLevelWrapper levelWrapper, ServerLevel level, NoiseBasedChunkGenerator generator) {
        this.levelWrapper = levelWrapper;
        this.stats = DhTurbo.DEBUG ? new GeneratorStats(level, levelWrapper.getDimensionName()) : null;
        // A world created without structures gets none in the distance either.
        boolean structures = level.getServer().getWorldData().worldGenOptions().generateStructures();
        this.writer = new LodTileWriter(levelWrapper, generator, level.registryAccess(), level.dimensionType(),
                level.getSeed(), UnaryOperator.identity(), structures ? level.getStructureManager() : null, stats,
                () -> Players.positions(level));
    }

    /** The generator answering requests right now: DH's own one while DH Turbo is switched off. */
    private IDhApiWorldGenerator delegate() {
        return DhIntegration.active() ? null : dhGenerator();
    }

    /** DH's own generator, created on first use; null when it cannot be created. */
    private IDhApiWorldGenerator dhGenerator() {
        if (dhGeneratorUnavailable) {
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
        if (delegate == null && wantsRealChunks(lodPosX, lodPosZ, detailLevel)) {
            delegate = dhGenerator();
        }
        if (delegate != null) {
            return delegate.generateLod(chunkPosMinX, chunkPosMinZ, lodPosX, lodPosZ, detailLevel, dataSource,
                    generatorMode, dhThreadPool, resultConsumer);
        }
        boolean fullDetail = detailLevel == 0;
        long sectionPos = DhSectionPos.encode((byte) (detailLevel + 6), lodPosX, lodPosZ);
        int minX = chunkPosMinX * 16;
        int minZ = chunkPosMinZ * 16;
        int spacing = 1 << detailLevel;
        int halfWidth = dataSource.getWidthInDataColumns() * spacing / 2;
        if (stats != null) {
            stats.request(detailLevel, lodPosX, lodPosZ, minX + halfWidth, minZ + halfWidth);
        }
        return CompletableFuture.runAsync(() -> {
            long start = TurboActivity.started();
            try {
                writer.fill(minX, minZ, spacing, dataSource);
            } catch (RuntimeException e) {
                TurboActivity.failed();
                // DH swallows failed futures silently; without this a broken tile is just a hole in the horizon.
                if (stats != null) {
                    stats.error();
                }
                if (loggedErrors.incrementAndGet() <= 10) {
                    DhTurbo.LOGGER.error("Failed to generate LOD tile at block {} {}, detail {}", minX, minZ, detailLevel, e);
                }
                throw e;
            }
            TurboActivity.finished(start);
            if (fullDetail && Config.REAL_CHUNKS_NEARBY.get()) {
                if (fastDetailTiles.size() > FAST_TILES_KEPT) {
                    fastDetailTiles.clear();
                }
                fastDetailTiles.add(sectionPos);
            }
            resultConsumer.accept(dataSource);
        }, WorkerPool.get());
    }

    /**
     * With {@code real_chunks_nearby} and a DH plan that generates chunks: whether this request is the chunk pass. Under
     * {@code SURFACE_THEN_CHUNKS} DH asks again for full-detail tiles until they come from real chunks, so a tile this
     * generator already filled is handed to DH's generator; the first request still gets the fast tile. Under
     * {@code CHUNKS_ONLY} every request goes to DH.
     */
    private boolean wantsRealChunks(int lodPosX, int lodPosZ, byte detailLevel) {
        if (!Config.REAL_CHUNKS_NEARBY.get()) {
            return false;
        }
        EDhApiGeneratorPlan plan = plan();
        if (plan == null || !plan.chunkGenEnabled) {
            return false;
        }
        if (!plan.surfaceGenEnabled) {
            return true;
        }
        if (detailLevel != 0) {
            return false;
        }
        long sectionPos = DhSectionPos.encode((byte) 6, lodPosX, lodPosZ);
        if (fastDetailTiles.remove(sectionPos)) {
            return true;
        }
        try {
            // A fast tile from an earlier session: its columns carry our step, real chunks carry LIGHT.
            ByteArrayList steps = new ByteArrayList();
            dhServerLevel().getFullDataProvider().repo.getColumnGenerationStepForPos(sectionPos, steps);
            for (int i = 0; i < steps.size(); i++) {
                if (steps.getByte(i) == EDhApiWorldGenerationStep.FEATURES.value) {
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // DH internals changed: keep generating fast.
        }
        return false;
    }

    private EDhApiGeneratorPlan plan() {
        try {
            return dhServerLevel().getFullDataProvider().getGeneratorPlan();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private AbstractDhServerLevel dhServerLevel() {
        return (AbstractDhServerLevel) SharedApi.getAbstractDhWorld().getLevel((ILevelWrapper) levelWrapper);
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
        writer.close();
        IDhApiWorldGenerator generator = dhGenerator;
        if (generator != null) {
            generator.close();
        }
    }
}
