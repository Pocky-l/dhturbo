package com.pockyl.dhturbo.surface;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import it.unimi.dsi.fastutil.ints.IntRBTreeSet;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import com.pockyl.dhturbo.DhTurbo;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * What grows and stands on the terrain of worlds without a single surface: chorus and small islands in the End, huge
 * fungi, vines, basalt and glowstone in the Nether. The game's own features run with its own seeds on a {@link VirtualLevel}
 * over the full profile of each column, like {@link RealTrees} does on a height field. Blocks of the terrain are worked out
 * only where a feature asks, since a chunk touches a few columns and a tile holds hundreds of chunks.
 */
final class ProfileFeatures {
    /** Columns at most this wide get the features; farther away they are lost in a single column anyway. */
    private static final int MAX_SPACING = 4;
    /** Under a ceiling features are denser and only seen from close by: a tile took 3.7 s with 4-block columns. */
    private static final int MAX_SPACING_UNDER_CEILING = 2;
    /** Chunks this far from a tile are replayed: a feature reaches into the chunks around its own. */
    private static final int CHUNK_REACH = 16;
    private static final GenerationStep.Decoration[] STEPS = {GenerationStep.Decoration.RAW_GENERATION,
            GenerationStep.Decoration.SURFACE_STRUCTURES, GenerationStep.Decoration.UNDERGROUND_DECORATION,
            GenerationStep.Decoration.VEGETAL_DECORATION};
    /** Inside the rock or too small to see. */
    private static final Set<Feature<?>> HIDDEN = Set.of(Feature.ORE, Feature.SCATTERED_ORE, Feature.SPRING, Feature.REPLACE_BLOBS);
    private static final Map<String, Boolean> REPORTED = new ConcurrentHashMap<>();

    private final NoiseBasedChunkGenerator generator;
    private final SurfaceSampler sampler;
    private final ProfileTerrain profile;
    @Nullable
    private final VanillaSurface surface;
    private final long seed;
    private final RegistryAccess registries;
    private final DimensionType dimensionType;
    private final int minY;
    private final int height;
    private final int seaLevel;
    /** Per step of {@link #STEPS}: the features of the step in vanilla's order, and per biome the ones replayed. */
    private final List<List<PlacedFeature>> stepFeatures = new ArrayList<>();
    private final List<Map<Holder<Biome>, int[]>> biomeFeatures = new ArrayList<>();
    private final LongAdder failures = new LongAdder();

    ProfileFeatures(NoiseBasedChunkGenerator generator, SurfaceSampler sampler, ProfileTerrain profile, @Nullable VanillaSurface surface,
                    long seed, RegistryAccess registries, DimensionType dimensionType) {
        this.generator = generator;
        this.sampler = sampler;
        this.profile = profile;
        this.surface = surface;
        this.seed = seed;
        this.registries = registries;
        this.dimensionType = dimensionType;
        this.minY = sampler.minY();
        this.height = sampler.maxY() - sampler.minY();
        this.seaLevel = sampler.seaLevel();
        // Same call as ChunkGenerator's own, so feature indices (and seeds) are identical.
        List<FeatureSorter.StepFeatureData> perStep = FeatureSorter.buildFeaturesPerStep(List.copyOf(generator.getBiomeSource().possibleBiomes()),
                biome -> generator.getBiomeGenerationSettings(biome).features(), true);
        for (GenerationStep.Decoration step : STEPS) {
            int ordinal = step.ordinal();
            List<PlacedFeature> features = ordinal < perStep.size() ? perStep.get(ordinal).features() : List.of();
            Object2IntMap<PlacedFeature> indices = new Object2IntOpenHashMap<>();
            for (int i = 0; i < features.size(); i++) {
                indices.put(features.get(i), i);
            }
            Map<Holder<Biome>, int[]> perBiome = new HashMap<>();
            for (Holder<Biome> biome : generator.getBiomeSource().possibleBiomes()) {
                List<HolderSet<PlacedFeature>> steps = generator.getBiomeGenerationSettings(biome).features();
                if (ordinal >= steps.size()) {
                    continue;
                }
                Set<Integer> chosen = new HashSet<>();
                steps.get(ordinal).forEach(holder -> {
                    if (indices.containsKey(holder.value()) && visible(holder.value())) {
                        chosen.add(indices.getInt(holder.value()));
                    }
                });
                perBiome.put(biome, chosen.stream().mapToInt(Integer::intValue).sorted().toArray());
            }
            stepFeatures.add(features);
            biomeFeatures.add(perBiome);
        }
    }

    private static boolean visible(PlacedFeature feature) {
        return feature.getFeatures().noneMatch(configured -> HIDDEN.contains(configured.feature()));
    }

    /** Whether tiles with columns this wide get the features. */
    boolean reaches(int spacing) {
        return spacing <= (dimensionType.hasCeiling() ? MAX_SPACING_UNDER_CEILING : MAX_SPACING);
    }

    /** Features that failed on the virtual level since the last call. */
    long takeFailures() {
        return failures.sumThenReset();
    }

    /** What the features put into each column of a tile, or null where they left it alone. */
    RealStructures.Column[] plant(int minX, int minZ, int spacing, int width) {
        int size = width * spacing;
        int fromChunkX = (minX - CHUNK_REACH) >> 4;
        int fromChunkZ = (minZ - CHUNK_REACH) >> 4;
        int toChunkX = (minX + size + CHUNK_REACH - 1) >> 4;
        int toChunkZ = (minZ + size + CHUNK_REACH - 1) >> 4;
        List<VirtualLevel> rows = new ArrayList<>();
        List<Runnable> tasks = new ArrayList<>();
        for (int chunkZ = fromChunkZ; chunkZ <= toChunkZ; chunkZ++) {
            Ground ground = new Ground();
            VirtualLevel level = new VirtualLevel(ground, seed, minY, height, seaLevel, registries, dimensionType);
            rows.add(level);
            int row = chunkZ;
            tasks.add(() -> {
                WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0));
                for (int chunkX = fromChunkX; chunkX <= toChunkX; chunkX++) {
                    decorate(level, ground, random, chunkX << 4, row << 4);
                }
            });
        }
        SurfaceTile.run(tasks);
        List<Int2ObjectSortedMap<BlockState>> placed = new ArrayList<>(width * width);
        for (int i = 0; i < width * width; i++) {
            placed.add(null);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (VirtualLevel level : rows) {
            for (Long2ObjectMap.Entry<BlockState> entry : level.writes().long2ObjectEntrySet()) {
                pos.set(entry.getLongKey());
                int i = Math.floorDiv(pos.getX() - minX, spacing);
                int j = Math.floorDiv(pos.getZ() - minZ, spacing);
                BlockState state = entry.getValue();
                if (i < 0 || j < 0 || i >= width || j >= width) {
                    continue;
                }
                boolean corner = pos.getX() == minX + i * spacing && pos.getZ() == minZ + j * spacing;
                // Distant Horizons merges real columns slice by slice and only air loses, so a wide column holds a block
                // wherever any column under it does; what a feature cleared stays with the corner, like the ground.
                if (state.isAir() && !corner) {
                    continue;
                }
                Int2ObjectSortedMap<BlockState> column = placed.get(i + j * width);
                if (column == null) {
                    column = new Int2ObjectRBTreeMap<>();
                    placed.set(i + j * width, column);
                }
                if (corner || !column.containsKey(pos.getY())) {
                    column.put(pos.getY(), state);
                }
            }
        }
        RealStructures.Column[] columns = new RealStructures.Column[width * width];
        for (int i = 0; i < columns.length; i++) {
            if (placed.get(i) != null) {
                columns[i] = column(placed.get(i));
            }
        }
        return columns;
    }

    /** Runs of one block; the top stays unset, nothing on these columns gets snow. */
    private RealStructures.Column column(Int2ObjectSortedMap<BlockState> blocks) {
        List<int[]> ranges = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        BlockState run = null;
        int from = 0;
        int to = 0;
        for (Int2ObjectMap.Entry<BlockState> entry : blocks.int2ObjectEntrySet()) {
            if (run != null && (entry.getIntKey() != to || entry.getValue() != run)) {
                ranges.add(new int[]{from, to});
                states.add(run);
                run = null;
            }
            if (run == null) {
                run = entry.getValue();
                from = entry.getIntKey();
            }
            to = entry.getIntKey() + 1;
        }
        ranges.add(new int[]{from, to});
        states.add(run);
        int[] bottoms = new int[ranges.size()];
        int[] tops = new int[ranges.size()];
        for (int i = 0; i < bottoms.length; i++) {
            bottoms[i] = ranges.get(i)[0];
            tops[i] = ranges.get(i)[1];
        }
        return new RealStructures.Column(bottoms, tops, states.toArray(new BlockState[0]), minY);
    }

    private void decorate(VirtualLevel level, Ground ground, WorldgenRandom random, int blockX, int blockZ) {
        long decorationSeed = random.setDecorationSeed(seed, blockX, blockZ);
        BlockPos origin = new BlockPos(blockX, minY, blockZ);
        for (int s = 0; s < STEPS.length; s++) {
            Map<Holder<Biome>, int[]> perBiome = biomeFeatures.get(s);
            IntSortedSet features = new IntRBTreeSet();
            // Vanilla takes the features of every biome in the 3x3 chunks around; a point in each finds them.
            for (int dx = -8; dx <= 24; dx += 16) {
                for (int dz = -8; dz <= 24; dz += 16) {
                    int[] indices = perBiome.get(ground.biome(blockX + dx, minY + height / 2, blockZ + dz));
                    if (indices != null) {
                        for (int index : indices) {
                            features.add(index);
                        }
                    }
                }
            }
            for (int index : features) {
                random.setFeatureSeed(decorationSeed, index, STEPS[s].ordinal());
                PlacedFeature feature = stepFeatures.get(s).get(index);
                try {
                    feature.placeWithBiomeCheck(level.view, (ChunkGenerator) generator, random, origin);
                } catch (VirtualLevel.NoEntities e) {
                    // End crystals on the spikes: the blocks are placed by then.
                } catch (RuntimeException e) {
                    failures.increment();
                    String key = feature + ": " + e;
                    if (REPORTED.size() < 20 && REPORTED.putIfAbsent(key, Boolean.TRUE) == null) {
                        DhTurbo.LOGGER.info("Feature failed on the virtual level and is skipped there: {}", key, e);
                    }
                }
            }
        }
    }

    /** The terrain as features see it, for one row of chunks on one thread. */
    private final class Ground implements VirtualLevel.Terrain {
        private final ProfileTerrain.Columns columns = profile.columns();
        private final Long2ObjectOpenHashMap<VanillaSurface.Column> ruled = new Long2ObjectOpenHashMap<>();
        private final BlockState rock = generator.generatorSettings().value().defaultBlock();
        private final BlockState fluid = generator.generatorSettings().value().defaultFluid();

        @Override
        public int ground(int x, int z) {
            return columns.top(x, z);
        }

        @Override
        public BlockState top(int x, int z) {
            return rock;
        }

        @Override
        public Holder<Biome> biome(int x, int y, int z) {
            return sampler.biome(x, y, z);
        }

        @Override
        public Boolean solid(int x, int y, int z) {
            return columns.solid(x, y, z);
        }

        @Override
        public BlockState rock() {
            return rock;
        }

        @Override
        public BlockState fluid() {
            return fluid;
        }

        /** The block after the surface rules: fungi only grow on nylium, chorus on end stone. */
        @Override
        @Nullable
        public BlockState block(int x, int y, int z) {
            if (surface == null || y < minY || y >= minY + height) {
                return null;
            }
            VanillaSurface.Column column = ruled.computeIfAbsent(ChunkPos.asLong(x, z), key -> surface
                    .pass(x, z, (a, b) -> sampler.maxY(), false).profileColumn(x, z, columns.at(x, z)).copy());
            return y >= column.bottom() && y < column.top() ? column.at(y) : Blocks.AIR.defaultBlockState();
        }
    }
}
