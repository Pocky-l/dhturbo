package com.pockyl.dhturbo.surface;

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

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Builds the columns of a LOD tile from the terrain noise: surface, water, snow, ice, lakes, plants and trees. Knows
 * nothing about Distant Horizons, so the generator ({@code LodTileWriter}) and the comparison with real generation
 * ({@code compare/GenerationComparison}) run the same code.
 */
public final class ColumnBuilder {
    private static final int MAX_LIGHT = 15;
    private static final BlockState SNOW_LAYER = Blocks.SNOW.defaultBlockState();
    /**
     * Columns at most this wide get the game's own trees (when enabled); wider ones approximate trees. Only full-detail
     * tiles: with 2-block columns the replay tripled the cost of tiles that are already 500+ blocks away.
     */
    private static final int REAL_TREES_MAX_SPACING = 1;

    /** A run of one block in a column, bottom (inclusive) to top (exclusive), relative to the bottom of the level. */
    public record Layer(BlockState state, int skyLight, int bottom, int top) {
    }

    /** Receives the columns of a tile; the layer list is reused between calls. */
    public interface Sink {
        /** @param biome the column's biome as the level knows it */
        void column(int i, int j, Holder<Biome> biome, List<Layer> layers);
    }

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

    public ColumnBuilder(NoiseBasedChunkGenerator generator, RegistryAccess registries, DimensionType dimensionType,
                         long seed, UnaryOperator<Holder<Biome>> levelBiome) {
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
    }

    public SurfaceSampler sampler() {
        return sampler;
    }

    /**
     * Builds a tile of {@code width} x {@code width} columns {@code spacing} blocks apart, starting at block
     * (minX, minZ), and hands each column to the sink. Returns the number of tree features that failed.
     */
    public long build(int minX, int minZ, int spacing, int width, Sink sink) {
        boolean real = realTrees != null && spacing <= REAL_TREES_MAX_SPACING;
        // A margin of columns around the tile: slopes at the edges, and trees of neighbouring chunks.
        int margin = real ? RealTrees.MARGIN / spacing + 1 : 1;
        SurfaceTile area = SurfaceTile.generate(sampler, minX - margin * spacing, minZ - margin * spacing, spacing,
                width + 2 * margin, fullResolution ? 1 : 2);
        RealTrees.TileTrees trees = real ? realTrees.plant(area, minX, minZ, width) : null;
        RealLakes.Lake[] lakes = realLakes != null && spacing <= RealLakes.MAX_SPACING
                ? realLakes.plan(minX, minZ, spacing, width)
                : null;
        long failures = real ? realTrees.takeFailures() : 0;

        List<Layer> points = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < width; j++) {
                points.clear();
                int x = minX + i * spacing;
                int z = minZ + j * spacing;
                Holder<Biome> biome = levelBiome.apply(area.biome(i + margin, j + margin));
                FakeTrees.Canopy canopy = null;
                BlockState treeTop = null;
                if (trees != null) {
                    canopy = trees.canopies()[i + j * width];
                    treeTop = trees.tops()[i + j * width];
                } else if (fakeTrees) {
                    canopy = FakeTrees.at(seed, x, z, spacing, BiomeLook.of(biome));
                }
                RealLakes.Lake lake = lakes != null ? lakes[i + j * width] : null;
                if (lake != null) {
                    lakeColumn(points, lake);
                } else {
                    column(points, x, z, area, i + margin, j + margin, canopy, treeTop);
                }
                sink.column(i, j, biome, points);
            }
        }
        return failures;
    }

    /**
     * Builds a column bottom to top; heights in data points are relative to the bottom of the level.
     *
     * @param canopy  tree over the column, or null
     * @param treeTop top block the trees replaced (podzol under giant spruces), or null
     */
    private void column(List<Layer> points, int x, int z, SurfaceTile area, int i, int j,
                        FakeTrees.Canopy canopy, BlockState treeTop) {
        int ground = area.height(i, j);
        // The level's own holder: on a client only it carries biome tags (they come from the server).
        Holder<Biome> biome = levelBiome.apply(area.biome(i, j));
        int minY = sampler.minY();
        int top = sampler.maxY() - minY;
        int seaLevel = sampler.seaLevel();
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
                    points.add(new Layer(defaultBlock, light, 0, floorFrom));
                }
                points.add(new Layer(floor, light, floorFrom, groundRel));
            }
            boolean frozen = biome.value().coldEnoughToSnow(new BlockPos(x, seaLevel, z));
            if (frozen && depth >= 1) {
                if (depth > 1) {
                    points.add(new Layer(Blocks.WATER.defaultBlockState(), MAX_LIGHT - 1, groundRel, seaRel - 1));
                }
                points.add(new Layer(Blocks.ICE.defaultBlockState(), MAX_LIGHT, seaRel - 1, seaRel));
            } else {
                points.add(new Layer(Blocks.WATER.defaultBlockState(), MAX_LIGHT, groundRel, seaRel));
            }
            points.add(new Layer(Blocks.AIR.defaultBlockState(), MAX_LIGHT, seaRel, top));
            return;
        }

        BlockState surface;
        if (!overworld) {
            surface = defaultBlock;
        } else if (treeTop != null) {
            surface = treeTop;
        } else {
            surface = SurfaceDetail.top(biome, look, seed, x, ground, z, area.steep(i, j));
        }
        if (groundRel > 0) {
            layers(points, overworld ? biome : null, look, surface, groundRel);
        }
        int airFrom = groundRel;
        boolean groundSnow = overworld && canopy == null && groundRel + 1 < top
                && SurfaceDetail.snowLayerOn(biome, surface, x, ground, z);
        if (groundSnow) {
            points.add(new Layer(SNOW_LAYER, MAX_LIGHT, groundRel, groundRel + 1));
            airFrom = groundRel + 1;
        }
        BlockState plant = groundCover && overworld && canopy == null && !groundSnow && groundRel + 1 < top
                ? GroundCover.at(seed, x, ground, z, area.biome(i, j), biome, surface)
                : null;
        if (plant != null) {
            // One block of plant: Distant Horizons does not draw it, it tints the ground under it.
            points.add(new Layer(plant, MAX_LIGHT, groundRel, groundRel + 1));
            airFrom = groundRel + 1;
        }
        if (canopy != null && overworld) {
            int leavesBottom = Math.min(top, groundRel + Math.max(0, canopy.leavesBottom()));
            int leavesTop = Math.min(top, groundRel + canopy.leavesTop());
            if (leavesBottom > groundRel) {
                BlockState under = canopy.trunk() ? canopy.log() : Blocks.AIR.defaultBlockState();
                points.add(new Layer(under, MAX_LIGHT, groundRel, leavesBottom));
            }
            if (leavesTop > leavesBottom) {
                points.add(new Layer(canopy.leaves(), MAX_LIGHT, leavesBottom, leavesTop));
            }
            airFrom = Math.max(groundRel, Math.max(leavesBottom, leavesTop));
            BlockState crown = leavesTop > leavesBottom ? canopy.leaves() : canopy.log();
            if (airFrom > groundRel && airFrom + 1 < top
                    && SurfaceDetail.snowLayerOn(biome, crown, x, airFrom + sampler.minY(), z)) {
                points.add(new Layer(SNOW_LAYER, MAX_LIGHT, airFrom, airFrom + 1));
                airFrom++;
            }
        }
        if (airFrom < top) {
            points.add(new Layer(Blocks.AIR.defaultBlockState(), MAX_LIGHT, airFrom, top));
        }
    }

    /** A column of a lake: rock up to the lake bed, the fluid (lava glows), air above. */
    private void lakeColumn(List<Layer> points, RealLakes.Lake lake) {
        int minY = sampler.minY();
        int top = sampler.maxY() - minY;
        int bottomRel = Math.clamp(lake.bottom() - minY, 0, top);
        int topRel = Math.clamp(lake.top() - minY, bottomRel, top);
        if (bottomRel > 0) {
            points.add(new Layer(defaultBlock, MAX_LIGHT, 0, bottomRel));
        }
        if (topRel > bottomRel) {
            points.add(new Layer(lake.fluid(), MAX_LIGHT, bottomRel, topRel));
        }
        if (topRel < top) {
            points.add(new Layer(Blocks.AIR.defaultBlockState(), MAX_LIGHT, topRel, top));
        }
    }

    /**
     * The solid part of a land column: rock, the layer under the top block (dirt under grass, sandstone under sand,
     * clay bands in badlands) and the top block. Distant Horizons draws these on the walls of cliffs and ravines.
     *
     * @param biome null outside the overworld (rock with the top block only)
     */
    private void layers(List<Layer> points, @Nullable Holder<Biome> biome, BiomeLook look,
                        BlockState surface, int groundRel) {
        int topFrom = groundRel - 1;
        if (biome != null && biome.is(BiomeTags.IS_BADLANDS)) {
            int bandFrom = Math.max(0, topFrom - SurfaceDetail.BAND_DEPTH);
            if (bandFrom > 0) {
                points.add(new Layer(defaultBlock, MAX_LIGHT, 0, bandFrom));
            }
            int minY = sampler.minY();
            int y = bandFrom;
            while (y < topFrom) {
                // Bands are two blocks high on even world Y.
                int end = Math.min(topFrom, y + 2 - Math.floorMod(y + minY, 2));
                points.add(new Layer(SurfaceDetail.band(seed, y + minY).defaultBlockState(), MAX_LIGHT, y, end));
                y = end;
            }
        } else {
            BlockState under = biome != null ? SurfaceDetail.subsurface(surface, look, defaultBlock) : defaultBlock;
            int underFrom = Math.max(0, topFrom - SurfaceDetail.SUBSURFACE_DEPTH);
            if (underFrom > 0) {
                points.add(new Layer(defaultBlock, MAX_LIGHT, 0, underFrom));
            }
            if (topFrom > underFrom) {
                points.add(new Layer(under, MAX_LIGHT, underFrom, topFrom));
            }
        }
        points.add(new Layer(surface, MAX_LIGHT, Math.max(0, topFrom), groundRel));
    }

}
