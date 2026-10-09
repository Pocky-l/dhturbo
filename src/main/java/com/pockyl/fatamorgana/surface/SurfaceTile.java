package com.pockyl.fatamorgana.surface;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * Surface heights and biomes of a square grid of columns, the unit an LOD generator works with.
 * Column {@code (i, j)} is at block {@code (minX + i * spacing, minZ + j * spacing)}.
 */
public final class SurfaceTile {
    /** Vanilla cell width: the terrain is interpolated between density samples this many blocks apart. */
    private static final int CELL_WIDTH = 4;

    public final int width;
    public final int[] heights;
    public final Holder<Biome>[] biomes;

    @SuppressWarnings("unchecked")
    private SurfaceTile(int width) {
        this.width = width;
        this.heights = new int[width * width];
        this.biomes = (Holder<Biome>[]) new Holder[width * width];
    }

    public int height(int i, int j) {
        return heights[i + j * width];
    }

    /**
     * Generates a tile.
     *
     * @param stride compute every {@code stride}-th column exactly and interpolate the rest; columns closer than a
     *               vanilla cell are always interpolated, since the real terrain is linear between cell corners anyway
     */
    public static SurfaceTile generate(SurfaceSampler sampler, int minX, int minZ, int spacing, int width, int stride) {
        SurfaceTile tile = new SurfaceTile(width);
        int step = Math.max(stride, Math.max(1, CELL_WIDTH / spacing));
        int samples = (width - 1 + step - 1) / step + 1;
        int[] lattice = new int[samples * samples];
        @SuppressWarnings("unchecked")
        Holder<Biome>[] latticeBiomes = (Holder<Biome>[]) new Holder[samples * samples];

        int hint = sampler.seaLevel();
        for (int sj = 0; sj < samples; sj++) {
            // Serpentine order keeps every sample next to the previous one, so its height is a good starting hint.
            boolean reverse = (sj & 1) == 1;
            for (int n = 0; n < samples; n++) {
                int si = reverse ? samples - 1 - n : n;
                int x = minX + si * step * spacing;
                int z = minZ + sj * step * spacing;
                int height = sampler.surfaceHeight(x, z, hint);
                lattice[si + sj * samples] = height;
                // Biomes come from climate noises at least as smooth as the terrain; the lattice is fine enough.
                latticeBiomes[si + sj * samples] = sampler.biome(x, Math.max(height, sampler.seaLevel()), z);
                hint = height;
            }
        }

        for (int j = 0; j < width; j++) {
            int sj = j / step;
            int nextJ = Math.min(sj + 1, samples - 1);
            double fz = (double) (j - sj * step) / step;
            for (int i = 0; i < width; i++) {
                int si = i / step;
                int nextI = Math.min(si + 1, samples - 1);
                double fx = (double) (i - si * step) / step;
                double top = lerp(fx, lattice[si + sj * samples], lattice[nextI + sj * samples]);
                double bottom = lerp(fx, lattice[si + nextJ * samples], lattice[nextI + nextJ * samples]);
                int height = (int) Math.round(lerp(fz, top, bottom));
                tile.heights[i + j * width] = height;
                int nearestI = fx < 0.5 ? si : nextI;
                int nearestJ = fz < 0.5 ? sj : nextJ;
                tile.biomes[i + j * width] = latticeBiomes[nearestI + nearestJ * samples];
            }
        }
        return tile;
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }
}
