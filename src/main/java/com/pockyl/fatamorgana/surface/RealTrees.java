package com.pockyl.fatamorgana.surface;

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
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;

/**
 * Trees exactly where the game will put them, without generating chunks.
 * <p>
 * Vanilla decorates a chunk by seeding one random per placed feature from the world seed, the chunk position and the
 * feature's index in its generation step. Seeds do not depend on other features, so the tree features of a chunk can
 * be replayed alone: placement modifiers and the tree feature run unchanged against a {@link VirtualLevel} standing
 * on the approximate terrain, which records the logs and leaves. Positions, species and shapes match the real trees
 * as long as the terrain height under them matches, which on cell corners it does exactly.
 */
public final class RealTrees {
    private static final int STEP = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
    /** Blocks around a tile whose trees are planted too: crowns reach a few blocks out of their chunk. */
    public static final int MARGIN = 16;

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
    /** Per biome: indices of its tree features in this step, ascending (vanilla's placement order). */
    private final Map<Holder<Biome>, int[]> biomeTrees = new HashMap<>();
    private final LongAdder failures = new LongAdder();

    public RealTrees(ChunkGenerator generator, SurfaceSampler sampler, long seed, RegistryAccess registries,
                     DimensionType dimensionType) {
        this.generator = generator;
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
        }
    }

    /** Features that failed on the virtual level since the last call (they called something it cannot do). */
    public long takeFailures() {
        return failures.sumThenReset();
    }

    /** Trees over a tile: crowns per column and top blocks the trees changed (podzol under giant spruces). */
    public record TileTrees(FakeTrees.Canopy[] canopies, BlockState[] tops) {
    }

    /**
     * Plants the trees of a tile of {@code width} columns starting at block {@code (minX, minZ)}. {@code area} must have
     * the tile's spacing and reach at least {@link #MARGIN} blocks beyond it on every side: trees of neighbouring chunks
     * grow into the tile.
     */
    public TileTrees plant(SurfaceTile area, int minX, int minZ, int width) {
        int spacing = area.spacing;
        VirtualLevel.Terrain terrain = new VirtualLevel.Terrain() {
            @Override
            public int ground(int x, int z) {
                return area.heightAt(x, z);
            }

            @Override
            public BlockState top(int x, int z) {
                return BiomeLook.of(area.biomeAt(x, z)).top();
            }

            @Override
            public Holder<Biome> biome(int x, int y, int z) {
                return sampler.biome(x, y, z);
            }
        };
        Long2ObjectMap<BlockState> writes = place(minX - MARGIN, minZ - MARGIN, width * spacing + 2 * MARGIN, terrain);

        int columns = width * width;
        int[] leavesBottom = new int[columns];
        int[] leavesTop = new int[columns];
        int[] logTop = new int[columns];
        Arrays.fill(leavesBottom, Integer.MAX_VALUE);
        Arrays.fill(leavesTop, Integer.MIN_VALUE);
        Arrays.fill(logTop, Integer.MIN_VALUE);
        BlockState[] leaves = new BlockState[columns];
        BlockState[] logs = new BlockState[columns];
        BlockState[] tops = new BlockState[columns];
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
            int index = i + j * width;
            int relative = pos.getY() - terrain.ground(pos.getX(), pos.getZ());
            if (relative == -1) {
                if (!state.is(BlockTags.LOGS) && !state.is(Blocks.DIRT)) {
                    tops[index] = state;
                }
            } else if (relative >= 0) {
                if (state.is(BlockTags.LOGS)) {
                    if (relative >= logTop[index]) {
                        logTop[index] = relative;
                        logs[index] = state;
                    }
                } else {
                    leavesBottom[index] = Math.min(leavesBottom[index], relative);
                    if (relative >= leavesTop[index]) {
                        leavesTop[index] = relative;
                        leaves[index] = state;
                    }
                }
            }
        }

        FakeTrees.Canopy[] canopies = new FakeTrees.Canopy[columns];
        for (int index = 0; index < columns; index++) {
            boolean hasLeaves = leaves[index] != null;
            boolean hasLog = logs[index] != null;
            if (hasLeaves) {
                int top = Math.max(leavesTop[index], logTop[index]) + 1;
                boolean trunk = hasLog && logTop[index] >= leavesBottom[index] - 1;
                canopies[index] = new FakeTrees.Canopy(leaves[index], hasLog ? logs[index] : leaves[index], trunk,
                        leavesBottom[index], top);
            } else if (hasLog) {
                canopies[index] = new FakeTrees.Canopy(logs[index], logs[index], true, logTop[index] + 1, logTop[index] + 1);
            }
        }
        return new TileTrees(canopies, tops);
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
                stepFeatures.get(index).placeWithBiomeCheck(level.proxy, generator, random, origin);
            } catch (RuntimeException e) {
                failures.increment();
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
            if (indices.containsKey(feature) && growsTrees(feature)) {
                result.add(indices.getInt(feature));
            }
        });
        return result.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    /** Trees and huge mushrooms, also inside random selectors (most tree features are selectors). */
    private static boolean growsTrees(PlacedFeature feature) {
        return feature.getFeatures().anyMatch(configured ->
                configured.feature() instanceof TreeFeature || configured.feature() instanceof AbstractHugeMushroomFeature);
    }
}
