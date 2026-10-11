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

import com.pockyl.dhturbo.surface.ColumnBuilder;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Writes LOD tiles of one level into Distant Horizons data sources; the columns come from {@link ColumnBuilder}.
 * Used by the server-side generator and by client-side generation in multiplayer.
 */
public final class LodTileWriter {
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

    public LodTileWriter(IDhApiLevelWrapper levelWrapper, NoiseBasedChunkGenerator generator, RegistryAccess registries,
                         DimensionType dimensionType, long seed, UnaryOperator<Holder<Biome>> levelBiome,
                         @Nullable GeneratorStats stats) {
        this.levelWrapper = levelWrapper;
        this.builder = new ColumnBuilder(generator, registries, dimensionType, seed, levelBiome);
        this.stats = stats;
    }

    /** Fills a DH data source of a tile starting at block (minX, minZ) with columns {@code spacing} blocks wide. */
    public void fill(int minX, int minZ, int spacing, IDhApiFullDataSource dataSource) {
        long start = System.nanoTime();
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
