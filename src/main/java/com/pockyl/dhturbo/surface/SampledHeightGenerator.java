package com.pockyl.dhturbo.surface;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Arrays;

/**
 * The world's generator with terrain heights from the {@link SurfaceSampler}: structure placement asks for heights
 * hundreds of times per village ({@code getFirstFreeHeight} for the start, every street and house), and vanilla answers
 * each by computing the density of the whole column. Here a height costs a few probes and the interpolation between
 * cell corners, the same terrain the distant tiles show. Caves and aquifers are not part of it.
 */
final class SampledHeightGenerator extends NoiseBasedChunkGenerator {
    /** Corner values per thread; reset when large (neighbouring calls share corners). */
    private static final int CORNER_CACHE = 8192;

    private final SurfaceSampler sampler;
    private final BlockState defaultBlock;
    private final BlockState defaultFluid;
    private final ThreadLocal<Long2DoubleOpenHashMap> corners = ThreadLocal.withInitial(Long2DoubleOpenHashMap::new);

    SampledHeightGenerator(NoiseBasedChunkGenerator generator, SurfaceSampler sampler) {
        super(generator.getBiomeSource(), generator.generatorSettings());
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        this.sampler = sampler;
        this.defaultBlock = settings.defaultBlock();
        this.defaultFluid = settings.defaultFluid();
    }

    /**
     * First non-solid Y of the column like the distant tiles have it (ignoring fluids): the cave-free surface, refined
     * between cell corners within a cell of it, so a cave opening there does not swallow the column.
     */
    int ground(int x, int z) {
        return search(x, z, false);
    }

    /**
     * First non-solid Y of the column as vanilla's heightmap search finds it: a cave opening at the surface lets the
     * search fall to the cave floor. Structure placement uses this (a village's layout depends on its heights).
     */
    int groundWithCaves(int x, int z) {
        return search(x, z, true);
    }

    private int search(int x, int z, boolean caves) {
        Long2DoubleOpenHashMap cache = corners.get();
        if (cache.size() > CORNER_CACHE) {
            cache.clear();
        }
        SurfaceSampler.CornerSource source = (cx, cy, cz) -> {
            long key = BlockPos.asLong(cx, cy, cz);
            double value = cache.getOrDefault(key, Double.NaN);
            if (Double.isNaN(value)) {
                value = sampler.realDensity(cx, cy, cz);
                cache.put(key, value);
            }
            return value;
        };
        int minY = sampler.minY();
        int maxY = sampler.maxY();
        int estimate = Math.clamp(sampler.surfaceHeight(x, z, sampler.seaLevel()), minY, maxY);
        int cell = sampler.cellHeight();
        if (caves) {
            int y = Math.min(estimate, maxY - 1);
            if (sampler.blockDensity(x, y, z, source) > 0) {
                while (y + 1 < maxY && sampler.blockDensity(x, y + 1, z, source) > 0) {
                    y++;
                }
                return y + 1;
            }
            while (y > minY && sampler.blockDensity(x, y - 1, z, source) <= 0) {
                y--;
            }
            return y;
        }
        int from = Math.min(maxY - 1, estimate + cell);
        int to = Math.max(minY, estimate - cell - 1);
        for (int y = from; y >= to; y--) {
            if (sampler.blockDensity(x, y, z, source) > 0) {
                return y == from ? estimate : y + 1;
            }
        }
        return estimate;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        int ground = groundWithCaves(x, z);
        int seaLevel = sampler.seaLevel();
        if (ground < seaLevel && type.isOpaque().test(defaultFluid)) {
            return seaLevel;
        }
        return Math.max(ground, level.getMinBuildHeight());
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor height, RandomState random) {
        int minY = height.getMinBuildHeight();
        BlockState[] states = new BlockState[height.getHeight()];
        Arrays.fill(states, Blocks.AIR.defaultBlockState());
        int ground = groundWithCaves(x, z);
        int seaLevel = sampler.seaLevel();
        for (int y = minY; y < Math.min(Math.max(ground, seaLevel), minY + states.length); y++) {
            states[y - minY] = y < ground ? defaultBlock : defaultFluid;
        }
        return new NoiseColumn(minY, states);
    }
}
