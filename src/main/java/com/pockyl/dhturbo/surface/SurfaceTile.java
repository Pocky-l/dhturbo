package com.pockyl.dhturbo.surface;

import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
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
    static final int CELL_WIDTH = 4;
    /** Sample rows per parallel band; small enough to spread a tile over many threads. */
    private static final int BAND_ROWS = 4;
    /** Rise over two blocks from which vanilla's surface rules call a slope steep. */
    private static final int VANILLA_STEEP_RISE = 4;
    /** Gradients between wide columns are averaged over a longer run and come out flatter than local ones. */
    private static final double COARSE_STEEP_FACTOR = 0.75;

    public final int minX;
    public final int minZ;
    public final int spacing;
    public final int width;
    public final int[] heights;
    public final Holder<Biome>[] biomes;

    @SuppressWarnings("unchecked")
    private SurfaceTile(int minX, int minZ, int spacing, int width) {
        this.minX = minX;
        this.minZ = minZ;
        this.spacing = spacing;
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

    /** Column index of block X (or Z), clamped to the tile. */
    public int index(int block, int min) {
        return Mth.clamp(Math.floorDiv(block - min, spacing), 0, width - 1);
    }

    /** Height of the column containing the block, the nearest edge column outside the tile. */
    public int heightAt(int x, int z) {
        return height(index(x, minX), index(z, minZ));
    }

    public Holder<Biome> biomeAt(int x, int z) {
        return biome(index(x, minX), index(z, minZ));
    }

    /**
     * Vanilla's {@code steep} surface condition: the ground rises by 4+ blocks over the two neighbours to the south
     * (a slope facing north) or to the west (facing east), neighbours clamped to the column's chunk; other directions
     * never count. On wider columns the same directions with the gradient over the neighbouring columns.
     */
    public boolean steep(int i, int j) {
        if (spacing == 1) {
            int x = minX + i;
            int z = minZ + j;
            int north = (z & 15) == 0 ? z : z - 1;
            int south = (z & 15) == 15 ? z : z + 1;
            if (heightAt(x, south) >= heightAt(x, north) + VANILLA_STEEP_RISE) {
                return true;
            }
            int west = (x & 15) == 0 ? x : x - 1;
            int east = (x & 15) == 15 ? x : x + 1;
            return heightAt(west, z) >= heightAt(east, z) + VANILLA_STEEP_RISE;
        }
        int north = Math.max(j - 1, 0);
        int south = Math.min(j + 1, width - 1);
        int west = Math.max(i - 1, 0);
        int east = Math.min(i + 1, width - 1);
        // Rise per two blocks, like vanilla's neighbours.
        double southward = south == north ? 0 : 2.0 * (height(i, south) - height(i, north)) / ((south - north) * spacing);
        double westward = east == west ? 0 : 2.0 * (height(west, j) - height(east, j)) / ((east - west) * spacing);
        return southward >= VANILLA_STEEP_RISE * COARSE_STEEP_FACTOR || westward >= VANILLA_STEEP_RISE * COARSE_STEEP_FACTOR;
    }

    /**
     * Generates a tile. When called from a fork-join worker, the tile is split into bands of rows computed in
     * parallel by that pool, so a single tile uses every worker.
     *
     * @param stride compute every {@code stride}-th column exactly and interpolate the rest; columns closer than a
     *               vanilla cell are always interpolated, since the real terrain is linear between cell corners anyway
     */
    public static SurfaceTile generate(SurfaceSampler sampler, int minX, int minZ, int spacing, int width, int stride) {
        return generate(sampler, minX, minZ, spacing, width, stride, true);
    }

    /**
     * @param interpolate on the cell lattice, interpolate the density between corners like vanilla (needs the corner
     *                    densities on every cell level the surface crosses); false keeps the corners' own heights and
     *                    lerps between them, several times cheaper
     */
    public static SurfaceTile generate(SurfaceSampler sampler, int minX, int minZ, int spacing, int width, int stride,
                                       boolean interpolate) {
        SurfaceTile tile = new SurfaceTile(minX, minZ, spacing, width);
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
        // On the cell lattice (detailed tiles) columns between corners get vanilla's own interpolation of the density.
        boolean cellLattice = interpolate && step * spacing == CELL_WIDTH;
        if (cellLattice) {
            List<Runnable> corners = new ArrayList<>();
            for (int first = 0; first < samples; first += BAND_ROWS) {
                int from = first;
                int to = Math.min(samples, first + BAND_ROWS);
                corners.add(() -> lattice.sampleCorners(sampler, minX, minZ, step * spacing, from, to));
            }
            run(corners);
        }

        for (int j = 0; j < width; j++) {
            int sj = j / step;
            int nextJ = Math.min(sj + 1, samples - 1);
            double fz = (double) (j - sj * step) / step;
            for (int i = 0; i < width; i++) {
                int si = i / step;
                int nextI = Math.min(si + 1, samples - 1);
                double fx = (double) (i - si * step) / step;
                int height = Integer.MIN_VALUE;
                if (cellLattice) {
                    // Corners too: their search ran without caves, vanilla's value at the corner has them.
                    height = lattice.interpolatedHeight(sampler.cellHeight(), si, sj, nextI, nextJ, fx, fz);
                }
                if (height == Integer.MIN_VALUE && fx == 0 && fz == 0) {
                    height = lattice.height(si, sj);
                }
                if (height == Integer.MIN_VALUE) {
                    double top = lerp(fx, lattice.height(si, sj), lattice.height(nextI, sj));
                    double bottom = lerp(fx, lattice.height(si, nextJ), lattice.height(nextI, nextJ));
                    height = (int) Math.round(lerp(fz, top, bottom));
                }
                tile.heights[i + j * width] = height;
                // On the cell lattice every sample is the corner of a vanilla biome quart (4x4 blocks): the column takes
                // its own quart's biome. Wider lattices take the nearest sample.
                tile.biomes[i + j * width] = cellLattice ? lattice.biome(si, sj)
                        : lattice.biome(fx < 0.5 ? si : nextI, fz < 0.5 ? sj : nextJ);
            }
        }
        return tile;
    }

    /** Runs the tasks in parallel when called from a fork-join worker, otherwise one after another. */
    static void run(List<Runnable> tasks) {
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
        /** Per corner: density on cell-corner levels from {@code levelBase} up (see {@link #sampleCorners}). */
        private final double[][] densities;
        private final int[] levelBase;

        @SuppressWarnings("unchecked")
        Lattice(int samples) {
            this.samples = samples;
            this.heights = new int[samples * samples];
            this.biomes = (Holder<Biome>[]) new Holder[samples * samples];
            this.densities = new double[samples * samples][];
            this.levelBase = new int[samples * samples];
        }

        /**
         * Samples the density of every corner on the cell levels the surface can cross in the four cells around it:
         * from the cell holding the lowest neighbouring surface to the one above the highest.
         */
        void sampleCorners(SurfaceSampler sampler, int minX, int minZ, int blockStep, int fromRow, int toRow) {
            int cell = sampler.cellHeight();
            int minY = sampler.minY();
            for (int sj = fromRow; sj < toRow; sj++) {
                for (int si = 0; si < samples; si++) {
                    int low = Integer.MAX_VALUE;
                    int high = Integer.MIN_VALUE;
                    for (int nj = Math.max(sj - 1, 0); nj <= Math.min(sj + 1, samples - 1); nj++) {
                        for (int ni = Math.max(si - 1, 0); ni <= Math.min(si + 1, samples - 1); ni++) {
                            low = Math.min(low, heights[ni + nj * samples]);
                            high = Math.max(high, heights[ni + nj * samples]);
                        }
                    }
                    int base = level(low - 1, minY, cell);
                    int top = level(high - 1, minY, cell) + cell;
                    int count = (top - base) / cell + 1;
                    double[] values = new double[count];
                    int x = minX + si * blockStep;
                    int z = minZ + sj * blockStep;
                    for (int k = 0; k < count; k++) {
                        values[k] = sampler.realDensity(x, base + k * cell, z);
                    }
                    densities[si + sj * samples] = values;
                    levelBase[si + sj * samples] = base;
                }
            }
        }

        /**
         * Vanilla's terrain between cell corners: the density is interpolated bilinearly between the four corners and
         * linearly between cell levels; the surface is the first non-solid block above the highest solid level.
         * Returns {@code Integer.MIN_VALUE} when the corners do not bracket the surface (then heights are lerped).
         */
        int interpolatedHeight(int cell, int si, int sj, int ni, int nj, double fx, double fz) {
            int[] corners = {si + sj * samples, ni + sj * samples, si + nj * samples, ni + nj * samples};
            double[] weights = {(1 - fx) * (1 - fz), fx * (1 - fz), (1 - fx) * fz, fx * fz};
            int base = Integer.MIN_VALUE;
            int top = Integer.MAX_VALUE;
            for (int corner : corners) {
                base = Math.max(base, levelBase[corner]);
                top = Math.min(top, levelBase[corner] + (densities[corner].length - 1) * cell);
            }
            if (top < base + cell) {
                return Integer.MIN_VALUE;
            }
            double above = density(corners, weights, top, cell);
            if (above > 0) {
                return Integer.MIN_VALUE;
            }
            for (int y = top - cell; y >= base; y -= cell) {
                double value = density(corners, weights, y, cell);
                if (value > 0) {
                    double blocks = value / (value - above) * cell;
                    return y + Math.min(cell, (int) Math.ceil(blocks));
                }
                above = value;
            }
            return Integer.MIN_VALUE;
        }

        private double density(int[] corners, double[] weights, int y, int cell) {
            double sum = 0;
            for (int c = 0; c < corners.length; c++) {
                int corner = corners[c];
                sum += weights[c] * densities[corner][(y - levelBase[corner]) / cell];
            }
            return sum;
        }

        private static int level(int y, int minY, int cell) {
            return Math.floorDiv(y - minY, cell) * cell + minY;
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
