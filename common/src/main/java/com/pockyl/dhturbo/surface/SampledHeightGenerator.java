package com.pockyl.dhturbo.surface;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

import org.jetbrains.annotations.Nullable;

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
    /** Full column profiles for worlds without a single surface; null for height fields. */
    @Nullable
    private final ProfileTerrain profile;
    private final ThreadLocal<ProfileTerrain.Columns[]> profileColumns = ThreadLocal.withInitial(() -> new ProfileTerrain.Columns[1]);
    private final BlockState defaultBlock;
    private final BlockState defaultFluid;
    private final ThreadLocal<Long2DoubleOpenHashMap> corners = ThreadLocal.withInitial(Long2DoubleOpenHashMap::new);
    /**
     * Heights found per thread, by column and kind: a village asks the same columns again for every piece it tries
     * there. Reset when large.
     */
    private final ThreadLocal<Long2IntOpenHashMap> heights = ThreadLocal.withInitial(() -> {
        Long2IntOpenHashMap map = new Long2IntOpenHashMap();
        map.defaultReturnValue(Integer.MIN_VALUE);
        return map;
    });
    private static final int HEIGHT_CACHE = 16384;

    SampledHeightGenerator(NoiseBasedChunkGenerator generator, SurfaceSampler sampler, @Nullable ProfileTerrain profile) {
        super(generator.getBiomeSource(), generator.generatorSettings());
        NoiseGeneratorSettings settings = generator.generatorSettings().value();
        this.sampler = sampler;
        this.profile = profile;
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

    /** This thread's column profiles (renewed when large). */
    ProfileTerrain.Columns profileColumns() {
        ProfileTerrain.Columns[] holder = profileColumns.get();
        if (holder[0] == null || ++uses[0] % 4096 == 0) {
            holder[0] = profile.columns();
        }
        return holder[0];
    }

    private final int[] uses = new int[1];

    private int search(int x, int z, boolean caves) {
        if (profile != null) {
            // Like vanilla's heightmap search from the top: in the Nether that is the roof.
            return profileColumns().top(x, z);
        }
        Long2IntOpenHashMap known = heights.get();
        long key = BlockPos.asLong(x, caves ? 1 : 0, z);
        int height = known.get(key);
        if (height == Integer.MIN_VALUE) {
            if (known.size() > HEIGHT_CACHE) {
                known.clear();
            }
            height = searchColumn(x, z, caves);
            known.put(key, height);
        }
        return height;
    }

    private int searchColumn(int x, int z, boolean caves) {
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
        if (profile != null) {
            ProfileTerrain.Columns columns = profileColumns();
            int seaLevel = sampler.seaLevel();
            for (int i = 0; i < states.length; i++) {
                int y = minY + i;
                if (columns.solid(x, y, z)) {
                    states[i] = defaultBlock;
                } else if (y < seaLevel) {
                    states[i] = defaultFluid;
                }
            }
            return new NoiseColumn(minY, states);
        }
        int ground = groundWithCaves(x, z);
        int seaLevel = sampler.seaLevel();
        for (int y = minY; y < Math.min(Math.max(ground, seaLevel), minY + states.length); y++) {
            states[y - minY] = y < ground ? defaultBlock : defaultFluid;
        }
        return new NoiseColumn(minY, states);
    }
}
