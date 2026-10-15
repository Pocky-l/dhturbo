package com.pockyl.dhturbo.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.surface.ColumnBuilder;
import com.pockyl.dhturbo.surface.RealStructures;
import com.pockyl.dhturbo.surface.StructurePrefetch;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Writes LOD tiles of one level into Distant Horizons data sources; the columns come from {@link ColumnBuilder}.
 * Used by the server-side generator and by client-side generation in multiplayer.
 */
public final class LodTileWriter implements AutoCloseable {
    /**
     * Generation step recorded for every column. DH re-requests a tile while its columns are below the step its
     * generator plan requires, which is {@code FEATURES} for full-detail tiles under the default
     * {@code SURFACE_THEN_CHUNKS} plan: marking our columns {@code SURFACE} made DH regenerate the tiles around the
     * player forever. Real chunks ({@code LIGHT}) still replace this data when the player loads them.
     */
    private static final EDhApiWorldGenerationStep STEP = EDhApiWorldGenerationStep.FEATURES;

    private final IDhApiLevelWrapper levelWrapper;
    private final ColumnBuilder builder;
    @Nullable
    private final GeneratorStats stats;
    private final Map<BlockState, IDhApiBlockStateWrapper> blockWrappers = new ConcurrentHashMap<>();
    private final Map<Holder<Biome>, IDhApiBiomeWrapper> biomeWrappers = new ConcurrentHashMap<>();
    @Nullable
    private final StructurePrefetch prefetch;
    private final Supplier<List<int[]>> players;

    /**
     * @param players block X and Z of the players this level's tiles are made for, read from generator threads; their
     *                structures are prepared ahead while the workers are idle
     */
    public LodTileWriter(IDhApiLevelWrapper levelWrapper, NoiseBasedChunkGenerator generator, RegistryAccess registries,
                         DimensionType dimensionType, long seed, UnaryOperator<Holder<Biome>> levelBiome,
                         @Nullable StructureTemplateManager templates, @Nullable GeneratorStats stats,
                         Supplier<List<int[]>> players) {
        this.levelWrapper = levelWrapper;
        this.builder = new ColumnBuilder(generator, registries, dimensionType, seed, levelBiome, templates);
        this.stats = stats;
        this.players = players;
        RealStructures structures = builder.realStructures();
        // Nothing is prepared while DH Turbo is switched off.
        this.prefetch = structures != null ? new StructurePrefetch(structures,
                () -> Config.ENABLED.get() ? players.get() : List.of(), WorkerPool.get()) : null;
    }

    /** Stops preparing structures ahead. */
    @Override
    public void close() {
        if (prefetch != null) {
            prefetch.close();
        }
    }

    /** Fills a DH data source of a tile starting at block (minX, minZ) with columns {@code spacing} blocks wide. */
    public void fill(int minX, int minZ, int spacing, IDhApiFullDataSource dataSource) {
        long start = System.nanoTime();
        if (prefetch != null) {
            int size = dataSource.getWidthInDataColumns() * spacing;
            prefetch.requested(nearestPlayer(minX + size / 2, minZ + size / 2), spacing);
            if (spacing <= RealStructures.MAX_SPACING) {
                prefetch.covered(minX, minZ, minX + size - 1, minZ + size - 1);
            }
        }
        List<DhApiTerrainDataPoint> points = new ArrayList<>();
        long failures = builder.build(minX, minZ, spacing, dataSource.getWidthInDataColumns(), (i, j, biome, layers) -> {
            points.clear();
            IDhApiBiomeWrapper biomeWrapper = biomeWrapper(biome);
            for (ColumnBuilder.Layer layer : layers) {
                points.add(DhApiTerrainDataPoint.create((byte) 0, layer.state().getLightEmission(), layer.skyLight(),
                        layer.bottom(), layer.top(), blockWrapper(layer.state()), biomeWrapper));
            }
            dataSource.setApiDataPointColumn(i, j, STEP, points);
        });
        if (stats != null) {
            stats.treeFailures(failures);
            stats.done(Integer.numberOfTrailingZeros(spacing), System.nanoTime() - start);
        }
    }

    /** Horizontal distance from the block to the nearest player, 0 without players. */
    private double nearestPlayer(int x, int z) {
        double nearest = Double.MAX_VALUE;
        for (int[] player : players.get()) {
            nearest = Math.min(nearest, Math.hypot(player[0] - x, player[1] - z));
        }
        return nearest == Double.MAX_VALUE ? 0 : nearest;
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
}
