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
import com.pockyl.dhturbo.surface.GroundCover;
import com.pockyl.dhturbo.surface.RealLakes;
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
    @Nullable
    private final RealLakes realLakes;
    private final BlockState defaultBlock;
    private final long seed;
    private final boolean fullResolution;
    private final boolean fakeTrees;
    private final boolean groundCover;
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
        RealLakes lakes = new RealLakes(generator, sampler, seed, registries, dimensionType);
        this.realLakes = lakes.isEmpty() ? null : lakes;
        this.defaultBlock = generator.generatorSettings().value().defaultBlock();
        this.seed = seed;
        this.fullResolution = Config.FULL_RESOLUTION.get();
        this.groundCover = Config.GROUND_COVER.get();
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
        RealLakes.Lake[] lakes = realLakes != null && spacing <= RealLakes.MAX_SPACING
                ? realLakes.plan(minX, minZ, spacing, width)
                : null;
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
                RealLakes.Lake lake = lakes != null ? lakes[i + j * width] : null;
                if (lake != null) {
                    lakeColumn(points, lake, levelBiome.apply(area.biome(i + margin, j + margin)));
                } else {
                    column(points, x, z, area, i + margin, j + margin, canopy, treeTop);
                }
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
                int light = Math.max(0, MAX_LIGHT - depth);
                int floorFrom = Math.max(0, groundRel - 1 - SurfaceDetail.SUBSURFACE_DEPTH);
                if (floorFrom > 0) {
                    points.add(point(defaultBlock, light, 0, floorFrom, biomeWrapper));
                }
                points.add(point(floor, light, floorFrom, groundRel, biomeWrapper));
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
            layers(points, overworld ? biome : null, look, surface, groundRel, biomeWrapper);
        }
        int airFrom = groundRel;
        BlockState plant = groundCover && overworld && canopy == null && groundRel + 1 < top
                ? GroundCover.at(seed, x, ground, z, area.biome(i, j), biome, surface)
                : null;
        if (plant != null) {
            // One block of plant: Distant Horizons does not draw it, it tints the ground under it.
            points.add(point(plant, MAX_LIGHT, groundRel, groundRel + 1, biomeWrapper));
            airFrom = groundRel + 1;
        }
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

    /** A column of a lake: rock up to the lake bed, the fluid (lava glows), air above. */
    private void lakeColumn(List<DhApiTerrainDataPoint> points, RealLakes.Lake lake, Holder<Biome> biome) {
        int minY = sampler.minY();
        int top = sampler.maxY() - minY;
        int bottomRel = Math.clamp(lake.bottom() - minY, 0, top);
        int topRel = Math.clamp(lake.top() - minY, bottomRel, top);
        IDhApiBiomeWrapper biomeWrapper = biomeWrapper(biome);
        if (bottomRel > 0) {
            points.add(point(defaultBlock, MAX_LIGHT, 0, bottomRel, biomeWrapper));
        }
        if (topRel > bottomRel) {
            points.add(point(lake.fluid(), MAX_LIGHT, bottomRel, topRel, biomeWrapper));
        }
        if (topRel < top) {
            points.add(point(Blocks.AIR.defaultBlockState(), MAX_LIGHT, topRel, top, biomeWrapper));
        }
    }

    /**
     * The solid part of a land column: rock, the layer under the top block (dirt under grass, sandstone under sand,
     * clay bands in badlands) and the top block. Distant Horizons draws these on the walls of cliffs and ravines.
     *
     * @param biome null outside the overworld (rock with the top block only)
     */
    private void layers(List<DhApiTerrainDataPoint> points, @Nullable Holder<Biome> biome, BiomeLook look,
                        BlockState surface, int groundRel, IDhApiBiomeWrapper biomeWrapper) {
        int topFrom = groundRel - 1;
        if (biome != null && biome.is(BiomeTags.IS_BADLANDS)) {
            int bandFrom = Math.max(0, topFrom - SurfaceDetail.BAND_DEPTH);
            if (bandFrom > 0) {
                points.add(point(defaultBlock, MAX_LIGHT, 0, bandFrom, biomeWrapper));
            }
            int minY = sampler.minY();
            int y = bandFrom;
            while (y < topFrom) {
                // Bands are two blocks high on even world Y.
                int end = Math.min(topFrom, y + 2 - Math.floorMod(y + minY, 2));
                points.add(point(SurfaceDetail.band(seed, y + minY).defaultBlockState(), MAX_LIGHT, y, end, biomeWrapper));
                y = end;
            }
        } else {
            BlockState under = biome != null ? SurfaceDetail.subsurface(surface, look, defaultBlock) : defaultBlock;
            int underFrom = Math.max(0, topFrom - SurfaceDetail.SUBSURFACE_DEPTH);
            if (underFrom > 0) {
                points.add(point(defaultBlock, MAX_LIGHT, 0, underFrom, biomeWrapper));
            }
            if (topFrom > underFrom) {
                points.add(point(under, MAX_LIGHT, underFrom, topFrom, biomeWrapper));
            }
        }
        points.add(point(surface, MAX_LIGHT, Math.max(0, topFrom), groundRel, biomeWrapper));
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
