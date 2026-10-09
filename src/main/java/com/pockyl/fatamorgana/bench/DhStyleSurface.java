package com.pockyl.fatamorgana.bench;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Reference implementation of the rough surface search used by Distant Horizons 3.3 on Minecraft 1.21.1, written
 * from its description, for benchmarking only: march the raw final density down from the top of the world in
 * 8-block steps, binary-search the last step, compute one column in four and interpolate the rest, sample the biome
 * of every column.
 */
final class DhStyleSurface {
    private static final int MARCH_STEP = 8;

    private final DensityFunction density;
    private final RandomState randomState;
    private final BiomeSource biomeSource;
    private final int minY;
    private final int maxY;

    DhStyleSurface(RandomState randomState, BiomeSource biomeSource, int minY, int maxY) {
        this.density = randomState.router().finalDensity();
        this.randomState = randomState;
        this.biomeSource = biomeSource;
        this.minY = minY;
        this.maxY = maxY;
    }

    int[] generate(int minX, int minZ, int spacing, int width, Holder<Biome>[] biomes) {
        int[] heights = new int[width * width];
        for (int i = 0; i < width; i += 2) {
            for (int j = 0; j < width; j += 2) {
                heights[i + j * width] = surfaceHeight(minX + i * spacing, minZ + j * spacing);
            }
        }
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < width; j++) {
                if ((i & 1) == 1 || (j & 1) == 1) {
                    heights[i + j * width] = interpolate(i, j, width, heights);
                }
                int x = minX + i * spacing;
                int z = minZ + j * spacing;
                biomes[i + j * width] = biomeSource.getNoiseBiome(QuartPos.fromBlock(x),
                        QuartPos.fromBlock(heights[i + j * width]), QuartPos.fromBlock(z), randomState.sampler());
            }
        }
        return heights;
    }

    int surfaceHeight(int x, int z) {
        int previous = maxY;
        for (int y = maxY - MARCH_STEP; y >= minY; y -= MARCH_STEP) {
            if (solid(x, y, z)) {
                int air = previous;
                int ground = y;
                while (air - ground > 1) {
                    int middle = (air + ground) / 2;
                    if (solid(x, middle, z)) {
                        ground = middle;
                    } else {
                        air = middle;
                    }
                }
                return ground + 1;
            }
            previous = y;
        }
        return minY;
    }

    private boolean solid(int x, int y, int z) {
        return density.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0;
    }

    private static int interpolate(int i, int j, int width, int[] heights) {
        int i0 = (i / 2) * 2;
        int j0 = (j / 2) * 2;
        int i1 = Math.min(i0 + 2, width - 2);
        int j1 = Math.min(j0 + 2, width - 2);
        double fx = i1 != i0 ? (double) (i - i0) / (i1 - i0) : 0;
        double fz = j1 != j0 ? (double) (j - j0) / (j1 - j0) : 0;
        double top = heights[i0 + width * j0] + (heights[i1 + width * j0] - heights[i0 + width * j0]) * fx;
        double bottom = heights[i0 + width * j1] + (heights[i1 + width * j1] - heights[i0 + width * j1]) * fx;
        return (int) Math.round(top + (bottom - top) * fz);
    }
}
