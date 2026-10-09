package com.pockyl.fatamorgana.surface;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;

/**
 * Surface heights and biomes of a square grid of columns, the unit an LOD generator works with.
 * Column {@code (i, j)} is at block {@code (minX + i * spacing, minZ + j * spacing)}.
 */
public final class SurfaceTile {
    /** Vanilla cell width: the terrain is interpolated between density samples this many blocks apart. */
    private static final int CELL_WIDTH = 4;
    /** Sample rows per parallel band; small enough to spread a tile over many threads. */
    private static final int BAND_ROWS = 4;

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

    public Holder<Biome> biome(int i, int j) {
        return biomes[i + j * width];
    }

    /**
     * Generates a tile. When called from a fork-join worker, the tile is split into bands of rows computed in
     * parallel by that pool, so a single tile uses every worker.
     *
     * @param stride compute every {@code stride}-th column exactly and interpolate the rest; columns closer than a
     *               vanilla cell are always interpolated, since the real terrain is linear between cell corners anyway
     */
    public static SurfaceTile generate(SurfaceSampler sampler, int minX, int minZ, int spacing, int width, int stride) {
        SurfaceTile tile = new SurfaceTile(width);
        int step = Math.max(stride, Math.max(1, CELL_WIDTH / spacing));
        int samples = (width - 1 + step - 1) / step + 1;
        Lattice lattice = new Lattice(samples);

        List<Runnable> bands = new ArrayList<>();
        for (int first = 0; first < samples; first += BAND_ROWS) {
            int from = first;
            int to = Math.min(samples, first + BAND_ROWS);
            bands.add(() -> lattice.sample(sampler, minX, minZ, step * spacing, from, to));
        }
        run(bands);

        for (int j = 0; j < width; j++) {
            int sj = j / step;
            int nextJ = Math.min(sj + 1, samples - 1);
            double fz = (double) (j - sj * step) / step;
            for (int i = 0; i < width; i++) {
                int si = i / step;
                int nextI = Math.min(si + 1, samples - 1);
                double fx = (double) (i - si * step) / step;
                double top = lerp(fx, lattice.height(si, sj), lattice.height(nextI, sj));
                double bottom = lerp(fx, lattice.height(si, nextJ), lattice.height(nextI, nextJ));
                tile.heights[i + j * width] = (int) Math.round(lerp(fz, top, bottom));
                tile.biomes[i + j * width] = lattice.biome(fx < 0.5 ? si : nextI, fz < 0.5 ? sj : nextJ);
            }
        }
        return tile;
    }

    private static void run(List<Runnable> tasks) {
        if (!ForkJoinTask.inForkJoinPool()) {
            tasks.forEach(Runnable::run);
            return;
        }
        List<RecursiveAction> actions = new ArrayList<>(tasks.size());
        for (Runnable task : tasks) {
            actions.add(new RecursiveAction() {
                @Override
                protected void compute() {
                    task.run();
                }
            });
        }
        ForkJoinTask.invokeAll(actions);
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }

    /** Exactly computed samples; each band writes its own rows only. */
    private static final class Lattice {
        private final int samples;
        private final int[] heights;
        private final Holder<Biome>[] biomes;

        @SuppressWarnings("unchecked")
        Lattice(int samples) {
            this.samples = samples;
            this.heights = new int[samples * samples];
            this.biomes = (Holder<Biome>[]) new Holder[samples * samples];
        }

        int height(int si, int sj) {
            return heights[si + sj * samples];
        }

        Holder<Biome> biome(int si, int sj) {
            return biomes[si + sj * samples];
        }

        void sample(SurfaceSampler sampler, int minX, int minZ, int blockStep, int fromRow, int toRow) {
            int hint = sampler.seaLevel();
            for (int sj = fromRow; sj < toRow; sj++) {
                // Serpentine order keeps every sample next to the previous one, so its height is a good starting hint.
                boolean reverse = ((sj - fromRow) & 1) == 1;
                for (int n = 0; n < samples; n++) {
                    int si = reverse ? samples - 1 - n : n;
                    int x = minX + si * blockStep;
                    int z = minZ + sj * blockStep;
                    int height = sampler.surfaceHeight(x, z, hint);
                    heights[si + sj * samples] = height;
                    // Climate noises are at least as smooth as the terrain, so biomes are sampled on the lattice too.
                    biomes[si + sj * samples] = sampler.biome(x, Math.max(height, sampler.seaLevel()), z);
                    hint = height;
                }
            }
        }
    }
}
