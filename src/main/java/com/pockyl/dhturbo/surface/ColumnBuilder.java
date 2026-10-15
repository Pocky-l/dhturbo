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
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Builds the columns of a LOD tile from the terrain noise: surface, water, snow, ice, lakes, plants, trees and
 * structures. Knows
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
    /** Rows of columns per parallel band. */
    private static final int BAND_ROWS = 8;

    /** A run of one block in a column, bottom (inclusive) to top (exclusive), relative to the bottom of the level. */
    public record Layer(BlockState state, int skyLight, int bottom, int top) {
    }

    /** Receives the columns of a tile; the layer list is reused between calls. */
    public interface Sink {
        /** @param biome the column's biome as the level knows it */
        void column(int i, int j, Holder<Biome> biome, List<Layer> layers);
    }

    private final SurfaceSampler sampler;
    /**
     * Terrain of a world without a single surface (the Nether's caverns, the End's islands), built from the full
     * vertical profile of every column; null for height-field worlds such as the overworld.
     */
    @Nullable
    private final ProfileTerrain profile;
    /** Sky light of open blocks: none in worlds without a sky. */
    private final int skyLight;
    /**
     * Top of the level relative to its bottom. The noise can end lower (the End's terrain stops at 128, its cities
     * reach 175); columns cover the whole level. */
    private final int levelTop;
    @Nullable
    private final RealTrees realTrees;
    @Nullable
    private final RealLakes realLakes;
    @Nullable
    private final RealStructures realStructures;
    /** The world's surface rules; null when they cannot run (then {@link BiomeLook} and {@link SurfaceDetail}). */
    @Nullable
    private final VanillaSurface vanillaSurface;
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

    /**
     * @param templates the structure templates of the world (its data packs), or null to leave structures out (also
     *                  when the world was created without structures)
     */
    public ColumnBuilder(NoiseBasedChunkGenerator generator, RegistryAccess registries, DimensionType dimensionType,
                         long seed, UnaryOperator<Holder<Biome>> levelBiome, @Nullable StructureTemplateManager templates) {
        this.sampler = new SurfaceSampler(registries, generator.generatorSettings().value(), generator.getBiomeSource(), seed);
        this.profile = profiled(dimensionType) ? new ProfileTerrain(sampler) : null;
        this.skyLight = dimensionType.hasSkyLight() ? MAX_LIGHT : 0;
        this.levelTop = Math.max(sampler.maxY(), dimensionType.minY() + dimensionType.height()) - sampler.minY();
        this.fakeTrees = Config.FAKE_TREES.get() && profile == null;
        this.realTrees = fakeTrees && Config.REAL_TREES.get()
                ? new RealTrees(generator, sampler, seed, registries, dimensionType)
                : null;
        RealLakes lakes = profile == null ? new RealLakes(generator, sampler, seed, registries, dimensionType) : null;
        this.realLakes = lakes == null || lakes.isEmpty() ? null : lakes;
        this.realStructures = templates != null && Config.STRUCTURES.get()
                ? realStructures(generator, sampler, templates, seed, registries, dimensionType)
                : null;
        this.vanillaSurface = Config.VANILLA_SURFACE.get() ? vanillaSurface(generator, sampler, registries, seed) : null;
        this.defaultBlock = generator.generatorSettings().value().defaultBlock();
        this.seed = seed;
        this.fullResolution = Config.FULL_RESOLUTION.get();
        this.groundCover = Config.GROUND_COVER.get() && profile == null;
        this.levelBiome = levelBiome;
    }

    @Nullable
    private static VanillaSurface vanillaSurface(NoiseBasedChunkGenerator generator, SurfaceSampler sampler,
                                                 RegistryAccess registries, long seed) {
        try {
            VanillaSurface surface = new VanillaSurface(generator, sampler, registries, seed);
            // A first column, so a rule set that cannot run here fails now and not in every tile.
            surface.pass(0, 0, (x, z) -> sampler.seaLevel(), true).column(0, 0, sampler.seaLevel());
            return surface;
        } catch (RuntimeException | LinkageError e) {
            DhTurbo.LOGGER.warn("The world's surface rules cannot run on distant terrain, using biome blocks instead", e);
            return null;
        }
    }

    /**
     * Worlds whose terrain is not one surface over solid rock: no sky (the Nether, the End) or a ceiling. Their columns
     * have caverns, islands and overhangs, so they are built from the full vertical profile.
     */
    public static boolean profiled(DimensionType dimensionType) {
        return !dimensionType.hasSkyLight() || dimensionType.hasCeiling();
    }

    @Nullable
    private static RealStructures realStructures(NoiseBasedChunkGenerator generator, SurfaceSampler sampler,
                                                 StructureTemplateManager templates, long seed, RegistryAccess registries,
                                                 DimensionType dimensionType) {
        try {
            RealStructures structures = new RealStructures(generator, sampler, templates, seed, registries, dimensionType);
            return structures.isEmpty() ? null : structures;
        } catch (RuntimeException | LinkageError e) {
            DhTurbo.LOGGER.warn("Structures cannot be placed on distant terrain of this world", e);
            return null;
        }
    }

    /** The structures of the world, or null when they are off or there are none. */
    @Nullable
    public RealStructures realStructures() {
        return realStructures;
    }

    /** The world's surface rules, or null when they are off or cannot run. */
    @Nullable
    public VanillaSurface vanillaSurface() {
        return vanillaSurface;
    }

    public SurfaceSampler sampler() {
        return sampler;
    }

    /**
     * Builds a tile of {@code width} x {@code width} columns {@code spacing} blocks apart, starting at block
     * (minX, minZ), and hands each column to the sink. Returns the number of tree features and structure pieces that
     * failed.
     */
    public long build(int minX, int minZ, int spacing, int width, Sink sink) {
        if (profile != null) {
            return buildProfiles(minX, minZ, spacing, width, sink);
        }
        boolean real = realTrees != null && spacing <= REAL_TREES_MAX_SPACING;
        // A margin of columns around the tile: slopes at the edges, and trees of neighbouring chunks.
        int margin = real ? RealTrees.MARGIN / spacing + 1 : 1;
        // Keep the area's lattice on vanilla's cell corners (multiples of 4 blocks): the tile's own corner is one.
        int cellColumns = Math.max(1, SurfaceTile.CELL_WIDTH / spacing);
        margin = (margin + cellColumns - 1) / cellColumns * cellColumns;
        SurfaceTile area = SurfaceTile.generate(sampler, minX - margin * spacing, minZ - margin * spacing, spacing,
                width + 2 * margin, fullResolution ? 1 : 2);
        List<RealStructures.Built> structures = realStructures != null && spacing <= RealStructures.MAX_SPACING
                ? realStructures.around(area.minX, area.minZ, area.minX + area.width * spacing - 1,
                        area.minZ + area.width * spacing - 1)
                : List.of();
        if (!structures.isEmpty()) {
            // Structures shape the ground around them before anything grows on it, as in chunk generation.
            for (int j = 0; j < area.width; j++) {
                for (int i = 0; i < area.width; i++) {
                    int ground = RealStructures.groundFor(structures, area.minX + i * spacing, area.minZ + j * spacing);
                    if (ground != Integer.MIN_VALUE) {
                        area.heights[i + j * area.width] = ground;
                    }
                }
            }
        }
        RealTrees.TileTrees trees = real ? realTrees.plant(area, minX, minZ, width) : null;
        RealLakes.Lake[] lakes = realLakes != null && spacing <= RealLakes.MAX_SPACING
                ? realLakes.plan(minX, minZ, spacing, width)
                : null;
        long failures = (real ? realTrees.takeFailures() : 0)
                + (realStructures != null ? realStructures.takeFailures() : 0);
        // Columns are built in parallel bands of rows (each with its own surface rule pass), then handed to the sink in
        // order on this thread.
        @SuppressWarnings("unchecked")
        List<Layer>[] columns = (List<Layer>[]) new List[width * width];
        int edge = margin;
        List<Runnable> bands = new ArrayList<>();
        for (int first = 0; first < width; first += BAND_ROWS) {
            int from = first;
            int to = Math.min(width, first + BAND_ROWS);
            bands.add(() -> {
                VanillaSurface.Pass rules = null;
                if (vanillaSurface != null) {
                    int seaLevel = sampler.seaLevel();
                    rules = vanillaSurface.pass(minX, minZ + from * spacing, spacing == 1
                            ? (x, z) -> Math.max(area.heightAt(x, z), seaLevel)
                            : (x, z) -> (int) Math.round(Math.max(blendedHeight(area, x, z), seaLevel)), spacing == 1);
                }
                for (int j = from; j < to; j++) {
                    for (int i = 0; i < width; i++) {
                        List<Layer> points = new ArrayList<>(8);
                        int x = minX + i * spacing;
                        int z = minZ + j * spacing;
                        FakeTrees.Canopy canopy = null;
                        BlockState treeTop = null;
                        if (trees != null) {
                            canopy = trees.canopies()[i + j * width];
                            treeTop = trees.tops()[i + j * width];
                        } else if (fakeTrees) {
                            canopy = FakeTrees.at(seed, x, z, spacing,
                                    BiomeLook.of(levelBiome.apply(area.biome(i + edge, j + edge))));
                        }
                        RealStructures.Column structure = structures.isEmpty() ? null
                                : RealStructures.columnFor(structures, x, z, spacing);
                        // Nothing grows where a structure stands on or above the ground.
                        boolean covered = structure != null && structure.top() > area.height(i + edge, j + edge);
                        if (covered) {
                            canopy = null;
                            treeTop = null;
                        }
                        RealLakes.Lake lake = lakes != null ? lakes[i + j * width] : null;
                        if (lake != null && !covered) {
                            lakeColumn(points, lake);
                        } else {
                            column(points, x, z, area, i + edge, j + edge, canopy, treeTop, rules, covered);
                        }
                        if (structure != null) {
                            overlay(points, structure, x, z, levelBiome.apply(area.biome(i + edge, j + edge)));
                        }
                        columns[i + j * width] = points;
                    }
                }
            });
        }
        SurfaceTile.run(bands);
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < width; j++) {
                sink.column(i, j, levelBiome.apply(area.biome(i + margin, j + margin)), columns[i + j * width]);
            }
        }
        return failures;
    }

    /**
     * A tile of a world without a single surface: every column's solid profile, fluid below sea level, the surface
     * rules over the whole column (netherrack, soul sand valleys, nylium, basalt deltas, bedrock floor and roof; end
     * stone), then structures.
     */
    private long buildProfiles(int minX, int minZ, int spacing, int width, Sink sink) {
        boolean[][] solids = profile.tile(minX, minZ, spacing, width);
        List<RealStructures.Built> structures = realStructures != null && spacing <= RealStructures.MAX_SPACING
                ? realStructures.around(minX, minZ, minX + width * spacing - 1, minZ + width * spacing - 1)
                : List.of();
        long failures = realStructures != null ? realStructures.takeFailures() : 0;
        @SuppressWarnings("unchecked")
        List<Layer>[] columns = (List<Layer>[]) new List[width * width];
        @SuppressWarnings("unchecked")
        Holder<Biome>[] biomes = (Holder<Biome>[]) new Holder[width * width];
        int minY = sampler.minY();
        List<Runnable> bands = new ArrayList<>();
        for (int first = 0; first < width; first += BAND_ROWS) {
            int from = first;
            int to = Math.min(width, first + BAND_ROWS);
            bands.add(() -> {
                VanillaSurface.Pass rules = vanillaSurface != null
                        ? vanillaSurface.pass(minX, minZ + from * spacing, (x, z) -> sampler.maxY(), spacing == 1)
                        : null;
                for (int j = from; j < to; j++) {
                    for (int i = 0; i < width; i++) {
                        int index = i + j * width;
                        int x = minX + i * spacing;
                        int z = minZ + j * spacing;
                        boolean[] solid = solids[index];
                        List<Layer> points = new ArrayList<>(16);
                        if (rules != null) {
                            VanillaSurface.Column column = rules.profileColumn(x, z, solid);
                            runs(points, column);
                        } else {
                            plainRuns(points, solid);
                        }
                        RealStructures.Column structure = structures.isEmpty() ? null
                                : RealStructures.columnFor(structures, x, z, spacing);
                        Holder<Biome> biome = levelBiome.apply(sampler.biome(x, Math.max(sampler.seaLevel(), minY), z));
                        if (structure != null) {
                            overlay(points, structure, x, z, biome);
                        }
                        columns[index] = points;
                        biomes[index] = biome;
                    }
                }
            });
        }
        SurfaceTile.run(bands);
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < width; j++) {
                sink.column(i, j, biomes[i + j * width], columns[i + j * width]);
            }
        }
        return failures;
    }

    /** Runs of one block state of a full column, bottom to top. */
    private void runs(List<Layer> points, VanillaSurface.Column column) {
        int minY = sampler.minY();
        int y = column.bottom();
        while (y < column.top()) {
            BlockState state = column.at(y);
            int end = y + 1;
            while (end < column.top() && column.at(end) == state) {
                end++;
            }
            points.add(new Layer(state, open(state) ? skyLight : 0, y - minY, end - minY));
            y = end;
        }
        int top = levelTop;
        if (column.top() - minY < top) {
            points.add(new Layer(Blocks.AIR.defaultBlockState(), skyLight, column.top() - minY, top));
        }
    }

    /** Runs of a profile without surface rules: the default block, fluid below sea level, air. */
    private void plainRuns(List<Layer> points, boolean[] solid) {
        int minY = sampler.minY();
        int seaLevel = sampler.seaLevel();
        BlockState fluid = Blocks.LAVA.defaultBlockState();
        int y = 0;
        while (y < solid.length) {
            boolean rock = solid[y];
            boolean wet = !rock && y + minY < seaLevel;
            int end = y + 1;
            while (end < solid.length && solid[end] == rock && (!rock && end + minY < seaLevel) == wet) {
                end++;
            }
            BlockState state = rock ? defaultBlock : wet ? fluid : Blocks.AIR.defaultBlockState();
            points.add(new Layer(state, open(state) ? skyLight : 0, y, end));
            y = end;
        }
    }

    private static boolean open(BlockState state) {
        return state.isAir() || state.liquid();
    }

    /**
     * Builds a column bottom to top; heights in data points are relative to the bottom of the level.
     *
     * @param canopy  tree over the column, or null
     * @param treeTop top block the trees replaced (podzol under giant spruces), or null
     */
    private void column(List<Layer> points, int x, int z, SurfaceTile area, int i, int j,
                        FakeTrees.Canopy canopy, BlockState treeTop, @Nullable VanillaSurface.Pass rules, boolean covered) {
        int ground = area.height(i, j);
        if (rules != null) {
            ruledColumn(points, x, z, area, i, j, canopy, treeTop, rules.column(x, z, ground), covered);
            return;
        }
        // The level's own holder: on a client only it carries biome tags (they come from the server).
        Holder<Biome> biome = levelBiome.apply(area.biome(i, j));
        int minY = sampler.minY();
        int top = levelTop;
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
        boolean groundSnow = overworld && canopy == null && !covered && groundRel + 1 < top
                && SurfaceDetail.snowLayerOn(biome, surface, x, ground, z);
        if (groundSnow) {
            points.add(new Layer(SNOW_LAYER, MAX_LIGHT, groundRel, groundRel + 1));
            airFrom = groundRel + 1;
        }
        BlockState plant = groundCover && overworld && canopy == null && !covered && !groundSnow && groundRel + 1 < top
                ? GroundCover.at(seed, x, ground, z, area.biome(i, j), biome, surface)
                : null;
        if (plant != null) {
            // One block of plant: Distant Horizons does not draw it, it tints the ground under it.
            points.add(new Layer(plant, MAX_LIGHT, groundRel, groundRel + 1));
            airFrom = groundRel + 1;
        }
        if (canopy != null && overworld) {
            airFrom = canopy(points, x, z, biome, canopy, groundRel, top);
        }
        if (airFrom < top) {
            points.add(new Layer(Blocks.AIR.defaultBlockState(), MAX_LIGHT, airFrom, top));
        }
    }

    /**
     * Height between wide columns, bilinear: the steep rule looks at the neighbouring blocks, which the tile does not
     * have; the slope between its columns stands in for theirs.
     */
    private static double blendedHeight(SurfaceTile area, int x, int z) {
        double fi = Math.clamp((double) (x - area.minX) / area.spacing, 0, area.width - 1);
        double fj = Math.clamp((double) (z - area.minZ) / area.spacing, 0, area.width - 1);
        int i = Math.min((int) fi, area.width - 2);
        int j = Math.min((int) fj, area.width - 2);
        double tx = fi - i;
        double tz = fj - j;
        double north = area.height(i, j) + (area.height(i + 1, j) - area.height(i, j)) * tx;
        double south = area.height(i, j + 1) + (area.height(i + 1, j + 1) - area.height(i, j + 1)) * tx;
        return north + (south - north) * tz;
    }

    /**
     * A column from the world's surface rules: the blocks they made (with icebergs and hoodoos), rock below, then what
     * later steps add on top: ice on cold water, snow layers, plants and trees.
     */
    private void ruledColumn(List<Layer> points, int x, int z, SurfaceTile area, int i, int j,
                             FakeTrees.Canopy canopy, BlockState treeTop, VanillaSurface.Column column,
                             boolean covered) {
        Holder<Biome> biome = levelBiome.apply(area.biome(i, j));
        int minY = sampler.minY();
        int top = levelTop;
        int columnTop = column.top();
        int waterTop = Integer.MIN_VALUE;
        for (int y = columnTop - 1; y >= column.bottom(); y--) {
            if (column.at(y).liquid()) {
                waterTop = y + 1;
                break;
            }
            if (!column.at(y).isAir()) {
                break;
            }
        }
        BlockState surfaceBlock = columnTop > column.bottom() ? column.at(columnTop - 1) : defaultBlock;
        boolean wet = surfaceBlock.liquid();
        // Vanilla's freeze_top_layer turns the top of cold water into ice.
        boolean frozen = wet && biome.value().coldEnoughToSnow(new BlockPos(x, columnTop - 1, z));
        if (!wet && treeTop != null) {
            surfaceBlock = treeTop;
        }
        int bottomRel = Math.max(column.bottom() - minY, 0);
        if (bottomRel > 0) {
            points.add(new Layer(defaultBlock, light(column.bottom() - 1, waterTop), 0, bottomRel));
        }
        int y = column.bottom();
        while (y < columnTop) {
            BlockState state = y == columnTop - 1 ? frozen ? Blocks.ICE.defaultBlockState() : surfaceBlock : column.at(y);
            int end = y + 1;
            while (end < columnTop - 1 && column.at(end) == state) {
                end++;
            }
            points.add(new Layer(state, state.liquid() ? MAX_LIGHT : light(end - 1, waterTop), y - minY, end - minY));
            y = end;
        }
        int groundRel = Math.max(columnTop - minY, 0);
        int airFrom = groundRel;
        if (wet) {
            if (airFrom < top) {
                points.add(new Layer(Blocks.AIR.defaultBlockState(), MAX_LIGHT, airFrom, top));
            }
            return;
        }
        int ground = columnTop;
        boolean groundSnow = canopy == null && !covered && groundRel + 1 < top
                && SurfaceDetail.snowLayerOn(biome, surfaceBlock, x, ground, z);
        if (groundSnow) {
            points.add(new Layer(SNOW_LAYER, MAX_LIGHT, groundRel, groundRel + 1));
            airFrom = groundRel + 1;
        }
        BlockState plant = groundCover && canopy == null && !covered && !groundSnow && groundRel + 1 < top
                ? GroundCover.at(seed, x, ground, z, area.biome(i, j), biome, surfaceBlock)
                : null;
        if (plant != null) {
            // One block of plant: Distant Horizons does not draw it, it tints the ground under it.
            points.add(new Layer(plant, MAX_LIGHT, groundRel, groundRel + 1));
            airFrom = groundRel + 1;
        }
        if (canopy != null) {
            airFrom = canopy(points, x, z, biome, canopy, groundRel, top);
        }
        if (airFrom < top) {
            points.add(new Layer(Blocks.AIR.defaultBlockState(), MAX_LIGHT, airFrom, top));
        }
    }

    /**
     * Puts a structure's blocks into a finished column: they replace whatever the column had at their heights, and a
     * snow layer settles on the top block where it is cold, like vanilla's {@code freeze_top_layer} after structures.
     */
    private void overlay(List<Layer> points, RealStructures.Column structure, int x, int z, Holder<Biome> biome) {
        int minY = sampler.minY();
        int top = levelTop;
        int waterTop = Integer.MIN_VALUE;
        for (Layer layer : points) {
            if (layer.state().liquid()) {
                waterTop = Math.max(waterTop, layer.top() + minY);
            }
        }
        List<Layer> runs = new ArrayList<>();
        for (int k = 0; k < structure.states().length; k++) {
            int bottom = Math.clamp(structure.bottoms()[k] - minY, 0, top);
            int runTop = Math.clamp(structure.tops()[k] - minY, 0, top);
            if (runTop > bottom) {
                BlockState state = structure.states()[k];
                int light = state.liquid() || state.isAir() ? MAX_LIGHT : light(runTop - 1 + minY, waterTop);
                runs.add(new Layer(state, light, bottom, runTop));
            }
        }
        if (runs.isEmpty()) {
            return;
        }
        List<Layer> result = new ArrayList<>(points.size() + runs.size() + 2);
        for (Layer layer : points) {
            int from = layer.bottom();
            for (Layer run : runs) {
                if (run.top() <= from || run.bottom() >= layer.top()) {
                    continue;
                }
                if (run.bottom() > from) {
                    result.add(new Layer(layer.state(), layer.skyLight(), from, run.bottom()));
                }
                from = Math.max(from, run.top());
            }
            if (from < layer.top()) {
                result.add(new Layer(layer.state(), layer.skyLight(), from, layer.top()));
            }
        }
        result.addAll(runs);
        result.sort(Comparator.comparingInt(Layer::bottom));
        int roof = structure.top() - minY;
        if (roof > 0 && roof + 1 < top) {
            for (int k = 1; k < result.size(); k++) {
                Layer layer = result.get(k);
                if (layer.bottom() == roof && layer.state().isAir()
                        && SurfaceDetail.snowLayerOn(biome, result.get(k - 1).state(), x, structure.top(), z)) {
                    result.set(k, new Layer(SNOW_LAYER, MAX_LIGHT, roof, roof + 1));
                    if (layer.top() > roof + 1) {
                        result.add(k + 1, new Layer(layer.state(), layer.skyLight(), roof + 1, layer.top()));
                    }
                    break;
                }
            }
        }
        points.clear();
        for (Layer layer : result) {
            Layer last = points.isEmpty() ? null : points.getLast();
            if (last != null && last.state() == layer.state() && last.skyLight() == layer.skyLight()
                    && last.top() == layer.bottom()) {
                points.set(points.size() - 1, new Layer(last.state(), last.skyLight(), last.bottom(), layer.top()));
            } else {
                points.add(layer);
            }
        }
    }

    /** Sky light of a block under water ({@code waterTop} = first block above the water, or none). */
    private static int light(int y, int waterTop) {
        return waterTop == Integer.MIN_VALUE || y >= waterTop ? MAX_LIGHT : Math.max(0, MAX_LIGHT - (waterTop - y));
    }

    /** Adds a tree's trunk and crown (and snow on it) above the ground; returns where the air starts. */
    private int canopy(List<Layer> points, int x, int z, Holder<Biome> biome, FakeTrees.Canopy canopy, int groundRel, int top) {
        int leavesBottom = Math.min(top, groundRel + Math.max(0, canopy.leavesBottom()));
        int leavesTop = Math.min(top, groundRel + canopy.leavesTop());
        if (leavesBottom > groundRel) {
            BlockState under = canopy.trunk() ? canopy.log() : Blocks.AIR.defaultBlockState();
            points.add(new Layer(under, MAX_LIGHT, groundRel, leavesBottom));
        }
        if (leavesTop > leavesBottom) {
            points.add(new Layer(canopy.leaves(), MAX_LIGHT, leavesBottom, leavesTop));
        }
        int airFrom = Math.max(groundRel, Math.max(leavesBottom, leavesTop));
        BlockState crown = leavesTop > leavesBottom ? canopy.leaves() : canopy.log();
        if (airFrom > groundRel && airFrom + 1 < top
                && SurfaceDetail.snowLayerOn(biome, crown, x, airFrom + sampler.minY(), z)) {
            points.add(new Layer(SNOW_LAYER, MAX_LIGHT, airFrom, airFrom + 1));
            airFrom++;
        }
        return airFrom;
    }

    /** A column of a lake: rock up to the lake bed, the fluid (lava glows), air above. */
    private void lakeColumn(List<Layer> points, RealLakes.Lake lake) {
        int minY = sampler.minY();
        int top = levelTop;
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
