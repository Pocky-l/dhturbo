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
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.world.version.WorldgenApi;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Trees, and with them the rest of the game's vegetation (grass, flowers, sugar cane, bamboo, pumpkins, berry bushes),
 * exactly where the game will put them, without generating chunks.
 * <p>
 * Vanilla decorates a chunk by seeding one random per placed feature from the world seed, the chunk position and the
 * feature's index in its generation step. Seeds do not depend on other features, so the tree features of a chunk can
 * be replayed alone: placement modifiers and the tree feature run unchanged against a {@link VirtualLevel} standing
 * on the approximate terrain, which records the logs and leaves. Positions, species and shapes match the real trees
 * as long as the terrain height under them matches, which on cell corners it does exactly.
 */
public final class RealTrees {
    private static final int STEP = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
    /**
     * Blocks of terrain needed around a tile: trees standing this close to it can reach in with their crowns. Trees of
     * every chunk touching the tile's surroundings are replayed, but farther ones only need rough heights.
     */
    public static final int MARGIN = 8;
    /** Chunks within this many blocks of a tile are replayed: a tree's origin can be anywhere in its chunk. */
    private static final int CHUNK_REACH = 8;

    private final ChunkGenerator generator;
    private final SurfaceSampler sampler;
    private final long seed;
    private final RegistryAccess registries;
    private final DimensionType dimensionType;
    private final int minY;
    private final int height;
    private final int seaLevel;
    private final List<PlacedFeature> stepFeatures;
    private final Object2IntMap<PlacedFeature> indices = new Object2IntOpenHashMap<>();
    /**
     * Per biome: indices of its features in this step that are replayed, ascending (vanilla's placement order): trees,
     * and with {@code plants} every other vegetation feature too.
     */
    private final Map<Holder<Biome>, int[]> biomeTrees = new HashMap<>();
    private final Set<Holder<Biome>> replayedTrees = new HashSet<>();
    private final boolean plants;
    private final LongAdder failures = new LongAdder();
    private static final Map<String, Boolean> REPORTED = new ConcurrentHashMap<>();

    /**
     * @param plants replay all vegetation features, not only trees: the columns then carry the game's own grass and
     *               flowers ({@link TileTrees#plants})
     */
    public RealTrees(ChunkGenerator generator, SurfaceSampler sampler, long seed, RegistryAccess registries,
                     DimensionType dimensionType, boolean plants) {
        this.generator = generator;
        this.plants = plants;
        this.sampler = sampler;
        this.seed = seed;
        this.registries = registries;
        this.dimensionType = dimensionType;
        this.minY = sampler.minY();
        this.height = sampler.maxY() - sampler.minY();
        this.seaLevel = sampler.seaLevel();
        // Same call as ChunkGenerator's own featuresPerStep, so feature indices (and seeds) are identical.
        List<FeatureSorter.StepFeatureData> perStep = FeatureSorter.buildFeaturesPerStep(
                List.copyOf(generator.getBiomeSource().possibleBiomes()),
                biome -> generator.getBiomeGenerationSettings(biome).features(), true);
        this.stepFeatures = STEP < perStep.size() ? perStep.get(STEP).features() : List.of();
        for (int i = 0; i < stepFeatures.size(); i++) {
            indices.put(stepFeatures.get(i), i);
        }
        for (Holder<Biome> biome : generator.getBiomeSource().possibleBiomes()) {
            biomeTrees.put(biome, treeIndices(biome));
            if (generator.getBiomeGenerationSettings(biome).features().size() > STEP
                    && generator.getBiomeGenerationSettings(biome).features().get(STEP).stream()
                    .anyMatch(holder -> growsTrees(holder.value()))) {
                replayedTrees.add(biome);
            }
        }
    }

    /**
     * Whether the biome's trees are replayed: it has the game's own tree features. Terrain mods may grow trees with
     * features of their own that need the real level (ReTerraForged's templates); such biomes get approximated trees.
     */
    public boolean replaysTrees(Holder<Biome> biome) {
        return replayedTrees.contains(biome);
    }

    /** Features that failed on the virtual level since the last call (they called something it cannot do). */
    public long takeFailures() {
        return failures.sumThenReset();
    }

    /**
     * Trees over a tile: crowns per column, top blocks the trees changed (podzol under giant spruces), and the small
     * plant standing on the ground (no collision: grass, flowers, sugar cane), or null.
     */
    public record TileTrees(FakeTrees.Canopy[] canopies, BlockState[] tops, BlockState[] plants) {
    }

    /** Whether the columns carry the game's own plants. */
    public boolean plants() {
        return plants;
    }

    /**
     * Plants the trees of a tile of {@code width} columns starting at block {@code (minX, minZ)}. {@code area} must have
     * the tile's spacing and reach at least {@link #MARGIN} blocks beyond it on every side: trees of neighbouring chunks
     * grow into the tile.
     */
    public TileTrees plant(SurfaceTile area, int minX, int minZ, int width) {
        return plant(area, minX, minZ, width, null);
    }

    /**
     * @param groundTops the top block of every column of {@code area} (index {@code i + j * area.width}) as the world's
     *             surface rules make it, or null for each biome's typical top block. Trees check the ground they grow
     *             on: with rule-made tops they skip the stone, moss and sand where the real world has no trees.
     */
    public TileTrees plant(SurfaceTile area, int minX, int minZ, int width, BlockState[] groundTops) {
        int spacing = area.spacing;
        VirtualLevel.Terrain terrain = new VirtualLevel.Terrain() {
            @Override
            public int ground(int x, int z) {
                return area.heightAt(x, z);
            }

            @Override
            public BlockState top(int x, int z) {
                if (groundTops != null) {
                    BlockState top = groundTops[area.index(x, area.minX) + area.index(z, area.minZ) * area.width];
                    if (top != null) {
                        return top;
                    }
                }
                return BiomeLook.of(area.biomeAt(x, z)).top();
            }

            @Override
            public Holder<Biome> biome(int x, int y, int z) {
                // Near the ground the tile already holds the biome at the same quart resolution vanilla uses (full
                // detail tiles are sampled every 4 blocks); only cave depths need a fresh lookup.
                int ground = area.heightAt(x, z);
                return Math.abs(y - ground) <= 8 ? area.biomeAt(x, z) : sampler.biome(x, y, z);
            }
        };
        Long2ObjectMap<BlockState> writes = place(minX - CHUNK_REACH, minZ - CHUNK_REACH, width * spacing + 2 * CHUNK_REACH, terrain);

        int columns = width * width;
        List<Int2ObjectSortedMap<BlockState>> placed = new ArrayList<>(Collections.nCopies(columns, null));
        BlockState[] tops = new BlockState[columns];
        BlockState[] plantsOnGround = new BlockState[columns];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Long2ObjectMap.Entry<BlockState> entry : writes.long2ObjectEntrySet()) {
            BlockState state = entry.getValue();
            if (state.isAir()) {
                continue;
            }
            pos.set(entry.getLongKey());
            int i = Math.floorDiv(pos.getX() - minX, spacing);
            int j = Math.floorDiv(pos.getZ() - minZ, spacing);
            if (i < 0 || j < 0 || i >= width || j >= width) {
                continue;
            }
            int cornerX = minX + i * spacing;
            int cornerZ = minZ + j * spacing;
            boolean corner = pos.getX() == cornerX && pos.getZ() == cornerZ;
            // Distant Horizons merges real columns slice by slice and only air loses: a wide column holds wood and leaves
            // wherever any column under it does, or forests come out thinner than the real ones. What lies on the ground
            // stays with the corner, like the ground itself.
            if (!corner && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS)) {
                continue;
            }
            int index = i + j * width;
            int relative = pos.getY() - terrain.ground(cornerX, cornerZ);
            if (relative == -1) {
                if (!state.is(BlockTags.LOGS) && !state.is(Blocks.DIRT)) {
                    tops[index] = state;
                }
            } else if (relative >= 0) {
                if (!WorldgenApi.blocksMotion(state) && !state.is(BlockTags.LEAVES)) {
                    // Grass, flowers, sugar cane: the lowest one stands on the ground.
                    if (relative == 0 && state.getFluidState().isEmpty()) {
                        plantsOnGround[index] = state;
                    }
                    continue;
                }
                Int2ObjectSortedMap<BlockState> blocks = placed.get(index);
                if (blocks == null) {
                    blocks = new Int2ObjectRBTreeMap<>();
                    placed.set(index, blocks);
                }
                if (corner || !blocks.containsKey(relative)) {
                    blocks.put(relative, state);
                }
            }
        }

        FakeTrees.Canopy[] canopies = new FakeTrees.Canopy[columns];
        boolean trunks = spacing <= FakeTrees.TRUNK_MAX_SPACING;
        for (int index = 0; index < columns; index++) {
            if (placed.get(index) != null) {
                canopies[index] = canopy(placed.get(index), trunks);
            }
        }
        return new TileTrees(canopies, tops, plantsOnGround);
    }

    /**
     * The blocks trees placed over a column, as they stand: a branch stays a branch in the air, and a bush under a high
     * crown stays apart from it.
     *
     * @param blocks the blocks by height above the ground
     * @param trunks false for columns wider than a trunk: wood under the column's lowest crown block is left out, a
     *               trunk would stand as wide as the column
     * @return the canopy, or null when nothing of it is left
     */
    @Nullable
    public static FakeTrees.Canopy canopy(Int2ObjectSortedMap<BlockState> blocks, boolean trunks) {
        List<FakeTrees.Span> spans = new ArrayList<>(2);
        boolean show = trunks;
        BlockState run = null;
        int from = 0;
        int to = 0;
        for (Int2ObjectMap.Entry<BlockState> entry : blocks.int2ObjectEntrySet()) {
            int y = entry.getIntKey();
            BlockState state = entry.getValue();
            show |= !isWood(state);
            if (!show) {
                continue;
            }
            if (run != null && (y != to || state.getBlock() != run.getBlock())) {
                spans.add(new FakeTrees.Span(run, from, to));
                run = null;
            }
            if (run == null) {
                run = state;
                from = y;
            }
            to = y + 1;
        }
        if (run == null) {
            return null;
        }
        spans.add(new FakeTrees.Span(run, from, to));
        return new FakeTrees.Canopy(spans);
    }

    /** What holds a tree up: logs, the stems of huge mushrooms, mangrove roots. */
    private static boolean isWood(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(Blocks.MUSHROOM_STEM) || state.is(Blocks.MANGROVE_ROOTS);
    }

    /**
     * Replays the tree features of every chunk overlapping the block area and returns the blocks they placed.
     * Trees of neighbouring chunks reach a few blocks in, so callers pass an area a chunk larger than they need.
     */
    Long2ObjectMap<BlockState> place(int minX, int minZ, int size, VirtualLevel.Terrain terrain) {
        int fromChunkX = minX >> 4;
        int fromChunkZ = minZ >> 4;
        int toChunkX = (minX + size - 1) >> 4;
        int toChunkZ = (minZ + size - 1) >> 4;
        // One virtual level per row of chunks, so rows can run on different workers. Trees of neighbouring rows then
        // do not see each other while growing; vanilla's result there depends on chunk generation order anyway.
        List<VirtualLevel> rows = new ArrayList<>();
        List<Runnable> tasks = new ArrayList<>();
        for (int chunkZ = fromChunkZ; chunkZ <= toChunkZ; chunkZ++) {
            VirtualLevel level = new VirtualLevel(terrain, seed, minY, height, seaLevel, registries, dimensionType);
            rows.add(level);
            int rowZ = chunkZ;
            tasks.add(() -> {
                WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0));
                for (int chunkX = fromChunkX; chunkX <= toChunkX; chunkX++) {
                    decorate(level, random, chunkX << 4, rowZ << 4, terrain);
                }
            });
        }
        SurfaceTile.run(tasks);
        Long2ObjectMap<BlockState> writes = new Long2ObjectOpenHashMap<>();
        for (VirtualLevel level : rows) {
            for (Long2ObjectMap.Entry<BlockState> entry : level.writes().long2ObjectEntrySet()) {
                BlockState previous = writes.get(entry.getLongKey());
                // Where crowns of two rows overlap keep what is there over air (a later row's removals).
                if (previous == null || !entry.getValue().isAir()) {
                    writes.put(entry.getLongKey(), entry.getValue());
                }
            }
        }
        return writes;
    }

    private void decorate(VirtualLevel level, WorldgenRandom random, int blockX, int blockZ, VirtualLevel.Terrain terrain) {
        IntSortedSet features = new IntRBTreeSet();
        // Vanilla takes the features of every biome in the 3x3 chunks around; sampling the surface around the chunk
        // finds the same ones for trees, which only grow on the surface.
        for (int dx = -8; dx <= 24; dx += 16) {
            for (int dz = -8; dz <= 24; dz += 16) {
                int x = blockX + dx;
                int z = blockZ + dz;
                int[] trees = biomeTrees.get(terrain.biome(x, terrain.ground(x, z), z));
                if (trees != null) {
                    for (int index : trees) {
                        features.add(index);
                    }
                }
            }
        }
        if (features.isEmpty()) {
            return;
        }
        long decorationSeed = random.setDecorationSeed(seed, blockX, blockZ);
        BlockPos origin = new BlockPos(blockX, minY, blockZ);
        for (int index : features) {
            random.setFeatureSeed(decorationSeed, index, STEP);
            try {
                WorldgenApi.place(stepFeatures.get(index), level.view, generator, random, origin);
            } catch (VirtualLevel.NoEntities e) {
                // Features that spawn something (bees in a nest) do so after placing their blocks.
            } catch (RuntimeException e) {
                failures.increment();
                String key = stepFeatures.get(index) + ": " + e;
                if (REPORTED.size() < 20 && REPORTED.putIfAbsent(key, Boolean.TRUE) == null) {
                    DhTurbo.LOGGER.info("Vegetation feature failed on the virtual level and is skipped there: {}", key, e);
                }
            }
        }
    }

    private int[] treeIndices(Holder<Biome> biome) {
        List<HolderSet<PlacedFeature>> steps = generator.getBiomeGenerationSettings(biome).features();
        if (STEP >= steps.size()) {
            return new int[0];
        }
        Set<Integer> result = new HashSet<>();
        steps.get(STEP).forEach(holder -> {
            PlacedFeature feature = holder.value();
            if (indices.containsKey(feature) && (growsTrees(feature) || plants && onSurface(feature))) {
                result.add(indices.getInt(feature));
            }
        });
        return result.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    /**
     * Vegetation placed on the surface (its placement goes through a heightmap): grass, flowers, sugar cane. Cave
     * vegetation (glow lichen, moss, dripleaves) is placed in a height range and never seen from afar.
     */
    private static boolean onSurface(PlacedFeature feature) {
        return feature.placement().stream().anyMatch(modifier -> modifier instanceof HeightmapPlacement);
    }

    /** Trees and huge mushrooms, also inside random selectors (most tree features are selectors). */
    private static boolean growsTrees(PlacedFeature feature) {
        return feature.getFeatures().anyMatch(holder ->
                holder.value() instanceof TreeFeature || holder.value() instanceof AbstractHugeMushroomFeature);
    }
}
