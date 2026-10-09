package com.pockyl.dhturbo.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.surface.BiomeLook;
import com.pockyl.dhturbo.surface.FakeTrees;
import com.pockyl.dhturbo.surface.RealTrees;
import com.pockyl.dhturbo.surface.SurfaceDetail;
import com.pockyl.dhturbo.surface.SurfaceSampler;
import com.pockyl.dhturbo.surface.SurfaceTile;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Writes LOD tiles of one level into Distant Horizons data sources: surface, water, snow, ice and trees straight from
 * the terrain noise. Used by the server-side generator and by client-side generation in multiplayer.
 */
public final class LodTileWriter {
    private static final int MAX_LIGHT = 15;
    /**
     * Generation step recorded for every column. DH re-requests a tile while its columns are below the step its
     * generator plan requires, which is {@code FEATURES} for full-detail tiles under the default
     * {@code SURFACE_THEN_CHUNKS} plan: marking our columns {@code SURFACE} made DH regenerate the tiles around the
     * player forever. Real chunks ({@code LIGHT}) still replace this data when the player loads them.
     */
    private static final EDhApiWorldGenerationStep STEP = EDhApiWorldGenerationStep.FEATURES;
    /**
     * Columns at most this wide get the game's own trees (when enabled); wider ones approximate trees. Only full-detail
     * tiles: with 2-block columns the replay tripled the cost of tiles that are already 500+ blocks away.
     */
    private static final int REAL_TREES_MAX_SPACING = 1;

    private final IDhApiLevelWrapper levelWrapper;
    private final SurfaceSampler sampler;
    @Nullable
    private final RealTrees realTrees;
    private final BlockState defaultBlock;
    private final long seed;
    private final boolean fullResolution;
    private final boolean fakeTrees;
    /**
     * Maps biomes of the generator's registries to the level's own. They differ on a client, where the generator's
     * registries come from local data packs without tags.
     */
    private final UnaryOperator<Holder<Biome>> levelBiome;
    @Nullable
    private final GeneratorStats stats;
    private final Map<BlockState, IDhApiBlockStateWrapper> blockWrappers = new ConcurrentHashMap<>();
    private final Map<Holder<Biome>, IDhApiBiomeWrapper> biomeWrappers = new ConcurrentHashMap<>();

    public LodTileWriter(IDhApiLevelWrapper levelWrapper, NoiseBasedChunkGenerator generator, RegistryAccess registries,
                         DimensionType dimensionType, long seed, UnaryOperator<Holder<Biome>> levelBiome,
                         @Nullable GeneratorStats stats) {
        this.levelWrapper = levelWrapper;
        this.sampler = new SurfaceSampler(registries, generator.generatorSettings().value(), generator.getBiomeSource(), seed);
        this.fakeTrees = Config.FAKE_TREES.get();
        this.realTrees = fakeTrees && Config.REAL_TREES.get()
                ? new RealTrees(generator, sampler, seed, registries, dimensionType)
                : null;
        this.defaultBlock = generator.generatorSettings().value().defaultBlock();
        this.seed = seed;
        this.fullResolution = Config.FULL_RESOLUTION.get();
        this.levelBiome = levelBiome;
        this.stats = stats;
    }

    /** Fills a DH data source of a tile starting at block (minX, minZ) with columns {@code spacing} blocks wide. */
    public void fill(int minX, int minZ, int spacing, IDhApiFullDataSource dataSource) {
        long start = System.nanoTime();
        int width = dataSource.getWidthInDataColumns();
        boolean real = realTrees != null && spacing <= REAL_TREES_MAX_SPACING;
        // A margin of columns around the tile: slopes at the edges, and trees of neighbouring chunks.
        int margin = real ? RealTrees.MARGIN / spacing + 1 : 1;
        SurfaceTile area = SurfaceTile.generate(sampler, minX - margin * spacing, minZ - margin * spacing, spacing,
                width + 2 * margin, fullResolution ? 1 : 2);
        RealTrees.TileTrees trees = real ? realTrees.plant(area, minX, minZ, width) : null;
        if (real) {
            long failures = realTrees.takeFailures();
            if (stats != null) {
                stats.treeFailures(failures);
            }
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
                    canopy = FakeTrees.at(seed, x, z, spacing, BiomeLook.of(levelBiome.apply(area.biome(i + margin, j + margin))));
                }
                column(points, x, z, area, i + margin, j + margin, canopy, treeTop);
                dataSource.setApiDataPointColumn(i, j, STEP, points);
            }
        }
        if (stats != null) {
            stats.done(Integer.numberOfTrailingZeros(spacing), System.nanoTime() - start);
        }
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
        // The level's own holder: on a client only it carries biome tags (they come from the server).
        Holder<Biome> biome = levelBiome.apply(area.biome(i, j));
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
}
