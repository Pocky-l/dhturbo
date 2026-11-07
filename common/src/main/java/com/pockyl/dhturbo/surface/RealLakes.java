package com.pockyl.dhturbo.surface;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Surface lakes (vanilla: lava lakes, one in ~200 chunks) exactly where the game puts them, without generating chunks.
 * <p>
 * Like {@link RealTrees}: each lake feature of the {@code LAKES} step is seeded per chunk from the world seed and its
 * index, and its placement modifiers run on a {@link VirtualLevel}. The lake shape and its checks are vanilla's
 * {@link LakeFeature} code repeated here, because the real feature needs chunk access the virtual level cannot give.
 * Terrain comes from the sampler (not the tile), so neighbouring tiles of any detail agree on every lake.
 */
public final class RealLakes {
    private static final int STEP = GenerationStep.Decoration.LAKES.ordinal();
    /** Lakes are at most 16 blocks wide: on wider columns they would hit too few sample points to show. */
    public static final int MAX_SPACING = 16;
    /** Terrain heights for lakes are sampled on this lattice and interpolated, like vanilla's noise cells. */
    private static final int LATTICE = 4;

    /** Fluid in a column: from {@code bottom} (inclusive) to {@code top} (exclusive), absolute Y. */
    public record Lake(BlockState fluid, int bottom, int top) {
    }

    private record SurfaceLake(PlacedFeature placed, LakeFeature.Configuration config, int index) {
    }

    private final ChunkGenerator generator;
    private final SurfaceSampler sampler;
    private final long seed;
    private final RegistryAccess registries;
    private final DimensionType dimensionType;
    private final List<SurfaceLake> lakes = new ArrayList<>();

    public RealLakes(ChunkGenerator generator, SurfaceSampler sampler, long seed, RegistryAccess registries,
                     DimensionType dimensionType) {
        this.generator = generator;
        this.sampler = sampler;
        this.seed = seed;
        this.registries = registries;
        this.dimensionType = dimensionType;
        List<FeatureSorter.StepFeatureData> perStep = FeatureSorter.buildFeaturesPerStep(
                List.copyOf(generator.getBiomeSource().possibleBiomes()),
                biome -> generator.getBiomeGenerationSettings(biome).features(), true);
        List<PlacedFeature> features = STEP < perStep.size() ? perStep.get(STEP).features() : List.of();
        for (int i = 0; i < features.size(); i++) {
            PlacedFeature placed = features.get(i);
            // Surface lakes stand on a heightmap; underground ones never reach the surface (vanilla keeps them 5+
            // blocks below it).
            boolean surface = placed.placement().stream().anyMatch(HeightmapPlacement.class::isInstance);
            ConfiguredFeature<?, ?> configured = placed.feature().value();
            if (surface && configured.feature() instanceof LakeFeature
                    && configured.config() instanceof LakeFeature.Configuration config) {
                lakes.add(new SurfaceLake(placed, config, i));
            }
        }
    }

    public boolean isEmpty() {
        return lakes.isEmpty();
    }

    /**
     * Lakes over the columns of a tile ({@code width} columns {@code spacing} blocks apart from block (minX, minZ)),
     * indexed {@code i + j * width}; null when the tile has none.
     */
    @Nullable
    public Lake[] plan(int minX, int minZ, int spacing, int width) {
        int size = width * spacing;
        // A lake starts up to 15 blocks into its chunk and is 16 blocks wide.
        int fromChunkX = (minX - 31) >> 4;
        int fromChunkZ = (minZ - 31) >> 4;
        int toChunkX = (minX + size - 1) >> 4;
        int toChunkZ = (minZ + size - 1) >> 4;
        Lake[] columns = new Lake[width * width];
        boolean[] any = new boolean[1];
        List<Runnable> rows = new ArrayList<>();
        for (int chunkZ = fromChunkZ; chunkZ <= toChunkZ; chunkZ++) {
            int rowZ = chunkZ;
            rows.add(() -> {
                Heights heights = new Heights();
                VirtualLevel level = new VirtualLevel(heights, seed, sampler.minY(), sampler.maxY() - sampler.minY(),
                        sampler.seaLevel(), registries, dimensionType);
                WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0));
                for (int chunkX = fromChunkX; chunkX <= toChunkX; chunkX++) {
                    decorate(level, heights, random, chunkX << 4, rowZ << 4, minX, minZ, spacing, width, columns, any);
                }
            });
        }
        SurfaceTile.run(rows);
        return any[0] ? columns : null;
    }

    private void decorate(VirtualLevel level, Heights heights, WorldgenRandom random, int blockX, int blockZ,
                          int minX, int minZ, int spacing, int width, Lake[] columns, boolean[] any) {
        long decorationSeed = random.setDecorationSeed(seed, blockX, blockZ);
        BlockPos origin = new BlockPos(blockX, sampler.minY(), blockZ);
        for (SurfaceLake lake : lakes) {
            random.setFeatureSeed(decorationSeed, lake.index, STEP);
            PlacementContext context = new PlacementContext(level.view, generator, Optional.of(lake.placed));
            Stream<BlockPos> positions = Stream.of(origin);
            for (PlacementModifier modifier : lake.placed.placement()) {
                positions = positions.flatMap(pos -> modifier.getPositions(context, random, pos));
            }
            // Lazily, like vanilla: modifiers and the feature draw from the random in turn.
            positions.forEach(pos -> place(level, random, pos, lake.config, minX, minZ, spacing, width, columns, any));
        }
    }

    /** {@link LakeFeature#place} on the approximate terrain, recording the fluid per tile column. */
    private void place(VirtualLevel level, WorldgenRandom random, BlockPos origin, LakeFeature.Configuration config,
                       int minX, int minZ, int spacing, int width, Lake[] columns, boolean[] any) {
        if (origin.getY() <= sampler.minY() + 4) {
            return;
        }
        BlockPos base = origin.below(4);
        boolean[] shape = new boolean[2048];
        int blobs = random.nextInt(4) + 4;
        for (int blob = 0; blob < blobs; blob++) {
            double sizeX = random.nextDouble() * 6.0 + 3.0;
            double sizeY = random.nextDouble() * 4.0 + 2.0;
            double sizeZ = random.nextDouble() * 6.0 + 3.0;
            double centerX = random.nextDouble() * (16.0 - sizeX - 2.0) + 1.0 + sizeX / 2.0;
            double centerY = random.nextDouble() * (8.0 - sizeY - 4.0) + 2.0 + sizeY / 2.0;
            double centerZ = random.nextDouble() * (16.0 - sizeZ - 2.0) + 1.0 + sizeZ / 2.0;
            for (int x = 1; x < 15; x++) {
                for (int z = 1; z < 15; z++) {
                    for (int y = 1; y < 7; y++) {
                        double dx = (x - centerX) / (sizeX / 2.0);
                        double dy = (y - centerY) / (sizeY / 2.0);
                        double dz = (z - centerZ) / (sizeZ / 2.0);
                        if (dx * dx + dy * dy + dz * dz < 1.0) {
                            shape[(x * 16 + z) * 8 + y] = true;
                        }
                    }
                }
            }
        }
        BlockState fluid = config.fluid().getState(random, base);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 8; y++) {
                    if (!shape[(x * 16 + z) * 8 + y] && touches(shape, x, y, z)) {
                        BlockState state = level.blockState(pos.setWithOffset(base, x, y, z));
                        if (y >= 4 && state.liquid()) {
                            return;
                        }
                        if (y < 4 && !state.isSolid() && state != fluid) {
                            return;
                        }
                    }
                }
            }
        }

        int size = width * spacing;
        for (int x = 0; x < 16; x++) {
            int blockX = base.getX() + x;
            if (blockX < minX || blockX >= minX + size || Math.floorMod(blockX - minX, spacing) != 0) {
                continue;
            }
            for (int z = 0; z < 16; z++) {
                int blockZ = base.getZ() + z;
                if (blockZ < minZ || blockZ >= minZ + size || Math.floorMod(blockZ - minZ, spacing) != 0) {
                    continue;
                }
                int bottom = Integer.MAX_VALUE;
                int top = Integer.MIN_VALUE;
                for (int y = 0; y < 4; y++) {
                    if (shape[(x * 16 + z) * 8 + y]) {
                        bottom = Math.min(bottom, base.getY() + y);
                        top = Math.max(top, base.getY() + y + 1);
                    }
                }
                // The lake carves air up to 4 blocks above its surface; under higher ground it stays hidden.
                if (top == Integer.MIN_VALUE || level.blockState(pos.set(blockX, base.getY() + 8, blockZ)).isSolid()) {
                    continue;
                }
                int index = (blockX - minX) / spacing + (blockZ - minZ) / spacing * width;
                synchronized (columns) {
                    if (columns[index] == null || columns[index].top < top) {
                        columns[index] = new Lake(fluid, bottom, top);
                        any[0] = true;
                    }
                }
            }
        }
    }

    private static boolean touches(boolean[] shape, int x, int y, int z) {
        return x < 15 && shape[((x + 1) * 16 + z) * 8 + y]
                || x > 0 && shape[((x - 1) * 16 + z) * 8 + y]
                || z < 15 && shape[(x * 16 + z + 1) * 8 + y]
                || z > 0 && shape[(x * 16 + (z - 1)) * 8 + y]
                || y < 7 && shape[(x * 16 + z) * 8 + y + 1]
                || y > 0 && shape[(x * 16 + z) * 8 + (y - 1)];
    }

    /** Terrain from the sampler: heights on a 4-block lattice, interpolated in between; cached per row of chunks. */
    private final class Heights implements VirtualLevel.Terrain {
        private final Long2IntMap lattice = new Long2IntOpenHashMap();
        private int hint = sampler.seaLevel();

        private int corner(int x, int z) {
            long key = ChunkPos.asLong(x, z);
            int height = lattice.getOrDefault(key, Integer.MIN_VALUE);
            if (height == Integer.MIN_VALUE) {
                height = sampler.surfaceHeight(x, z, hint);
                hint = height;
                lattice.put(key, height);
            }
            return height;
        }

        @Override
        public int ground(int x, int z) {
            int x0 = Math.floorDiv(x, LATTICE) * LATTICE;
            int z0 = Math.floorDiv(z, LATTICE) * LATTICE;
            double fx = (x - x0) / (double) LATTICE;
            double fz = (z - z0) / (double) LATTICE;
            double north = corner(x0, z0) * (1 - fx) + corner(x0 + LATTICE, z0) * fx;
            double south = corner(x0, z0 + LATTICE) * (1 - fx) + corner(x0 + LATTICE, z0 + LATTICE) * fx;
            return (int) Math.round(north * (1 - fz) + south * fz);
        }

        @Override
        public BlockState top(int x, int z) {
            // Lakes only ask whether blocks are solid.
            return Blocks.STONE.defaultBlockState();
        }

        @Override
        public Holder<Biome> biome(int x, int y, int z) {
            return sampler.biome(x, y, z);
        }
    }
}
