package com.pockyl.dhturbo.bench;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;

import com.pockyl.dhturbo.surface.RealTrees;
import com.pockyl.dhturbo.surface.SurfaceSampler;
import com.pockyl.dhturbo.surface.SurfaceTile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;

/**
 * Compares the surface search of DH Turbo with the Distant Horizons style search and with real vanilla heights
 * on the same LOD tiles: speed per tile, multithreaded throughput and height error.
 */
public final class SurfaceBenchmark {
    /** Columns per LOD tile side, as in Distant Horizons. */
    public static final int TILE_WIDTH = 64;
    private static final int[] SPACINGS = {1, 4, 16, 64};
    /** Every n-th column of a tile is compared with the real height (vanilla generation is slow). */
    private static final int ACCURACY_STEP = 8;

    private final SurfaceSampler fast;
    private final DhStyleSurface dhStyle;
    private final NoiseBasedChunkGenerator vanilla;
    private final RandomState randomState;
    private final LevelHeightAccessor heightAccessor;
    private final RealTrees realTrees;

    public SurfaceBenchmark(RegistryAccess registries, Holder<NoiseGeneratorSettings> settings, BiomeSource biomeSource,
                            long seed) {
        NoiseGeneratorSettings value = settings.value();
        this.fast = new SurfaceSampler(registries, value, biomeSource, seed);
        this.randomState = RandomState.create(value, registries.lookupOrThrow(Registries.NOISE), seed);
        this.dhStyle = new DhStyleSurface(randomState, biomeSource, fast.minY(), fast.maxY());
        this.vanilla = new NoiseBasedChunkGenerator(biomeSource, settings);
        this.heightAccessor = LevelHeightAccessor.create(value.noiseSettings().minY(), value.noiseSettings().height());
        this.realTrees = new RealTrees(vanilla, fast, seed, registries,
                registries.registryOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.OVERWORLD));
    }

    /** Result of planting the real trees of one tile. */
    public record Planted(int treeColumns, long failures, double millis) {
    }

    /** Plants the trees of a full-detail tile at the given corner, the way the generator does. */
    public Planted plant(int minX, int minZ, int spacing) {
        long start = System.nanoTime();
        int margin = RealTrees.MARGIN / spacing + 1;
        SurfaceTile area = SurfaceTile.generate(fast, minX - margin * spacing, minZ - margin * spacing, spacing,
                TILE_WIDTH + 2 * margin, 1);
        RealTrees.TileTrees trees = realTrees.plant(area, minX, minZ, TILE_WIDTH);
        double millis = (System.nanoTime() - start) / 1e6;
        int columns = 0;
        for (var canopy : trees.canopies()) {
            if (canopy != null) {
                columns++;
            }
        }
        return new Planted(columns, realTrees.takeFailures(), millis);
    }

    /** Result of comparing heights of one algorithm with vanilla. */
    public record Accuracy(int samples, double meanError, int p90Error, double withinTwo) {
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "mean %.1f, p90 %d, <=2 blocks %.0f%%", meanError, p90Error, withinTwo * 100);
        }
    }

    public int vanillaHeight(int x, int z) {
        return vanilla.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, randomState);
    }

    public SurfaceSampler fast() {
        return fast;
    }

    /** Runs the whole benchmark and returns the report, one line per entry. */
    public List<String> run(int tilesPerSpacing, int threads) {
        List<String> report = new ArrayList<>();
        Random random = new Random(1234);
        warmUp();
        for (int spacing : SPACINGS) {
            int[][] origins = origins(random, tilesPerSpacing, spacing);
            long dhNanos = time(origins, origin -> dhTile(origins[origin], spacing));
            long fastNanos = time(origins, origin -> fastTile(origins[origin], spacing, 1));
            long fastStrideNanos = time(origins, origin -> fastTile(origins[origin], spacing, 2));
            Accuracy dhAccuracy = accuracy(origins, spacing, true);
            Accuracy fastAccuracy = accuracy(origins, spacing, false);
            report.add(String.format(Locale.ROOT,
                    "detail %d (%d blocks/column): DH-style %.1f ms/tile, Turbo %.1f ms/tile (x%.1f), Turbo 1/4 columns %.1f ms (x%.1f)",
                    Integer.numberOfTrailingZeros(spacing), spacing, millis(dhNanos, origins.length),
                    millis(fastNanos, origins.length), (double) dhNanos / fastNanos,
                    millis(fastStrideNanos, origins.length), (double) dhNanos / fastStrideNanos));
            report.add("  height error vs vanilla: DH-style " + dhAccuracy + " | Turbo " + fastAccuracy);
        }
        for (int spacing : new int[]{1, 2}) {
            int[][] origins = origins(random, tilesPerSpacing, spacing);
            long surfaceOnly = time(origins, origin -> SurfaceTile.generate(fast, origins[origin][0] - 17 * spacing,
                    origins[origin][1] - 17 * spacing, spacing, TILE_WIDTH + 34, 1));
            double plantMillis = 0;
            long failures = 0;
            int columns = 0;
            for (int[] origin : origins) {
                Planted planted = plant(origin[0], origin[1], spacing);
                plantMillis += planted.millis();
                failures += planted.failures();
                columns += planted.treeColumns();
            }
            ForkJoinPool pool = new ForkJoinPool(threads);
            long split;
            try {
                split = time(origins, origin -> pool.invoke(ForkJoinTask.adapt(() -> plant(origins[origin][0], origins[origin][1], spacing))));
            } finally {
                pool.shutdown();
            }
            report.add(String.format(Locale.ROOT,
                    "real trees, detail %d: %.1f ms/tile with trees vs %.1f ms surface only (%.1f ms split over %d threads), %d tree columns per tile, %d failed features",
                    Integer.numberOfTrailingZeros(spacing), plantMillis / origins.length, millis(surfaceOnly, origins.length),
                    millis(split, origins.length), threads, columns / origins.length, failures));
        }
        if (threads > 1) {
            int[][] origins = origins(random, threads * 4, 16);
            double dhRate = throughput(origins, threads, origin -> dhTile(origins[origin], 16));
            double fastRate = throughput(origins, threads, origin -> fastTile(origins[origin], 16, 1));
            report.add(String.format(Locale.ROOT, "%d threads, detail 4: DH-style %.1f tiles/s, Turbo %.1f tiles/s (x%.1f)",
                    threads, dhRate, fastRate, fastRate / dhRate));
            ForkJoinPool pool = new ForkJoinPool(threads);
            try {
                long single = time(origins, origin -> fastTile(origins[origin], 16, 2));
                long split = time(origins, origin -> pool.invoke(ForkJoinTask.adapt(() -> fastTile(origins[origin], 16, 2))));
                report.add(String.format(Locale.ROOT, "one tile, detail 4, 1/4 columns: %.1f ms on 1 thread, %.1f ms split over %d threads",
                        millis(single, origins.length), millis(split, origins.length), threads));
            } finally {
                pool.shutdown();
            }
        }
        return report;
    }

    private void warmUp() {
        for (int i = 0; i < 3; i++) {
            dhTile(new int[]{i * 4096, 0}, 16);
            fastTile(new int[]{i * 4096, 0}, 16, 1);
            fastTile(new int[]{i * 4096, 0}, 16, 2);
        }
    }

    private void dhTile(int[] origin, int spacing) {
        @SuppressWarnings("unchecked")
        Holder<Biome>[] biomes = (Holder<Biome>[]) new Holder[TILE_WIDTH * TILE_WIDTH];
        dhStyle.generate(origin[0], origin[1], spacing, TILE_WIDTH, biomes);
    }

    private SurfaceTile fastTile(int[] origin, int spacing, int stride) {
        return SurfaceTile.generate(fast, origin[0], origin[1], spacing, TILE_WIDTH, stride);
    }

    private Accuracy accuracy(int[][] origins, int spacing, boolean dh) {
        int[] errors = new int[origins.length * (TILE_WIDTH / ACCURACY_STEP) * (TILE_WIDTH / ACCURACY_STEP)];
        int count = 0;
        for (int[] origin : origins) {
            int[] heights;
            if (dh) {
                @SuppressWarnings("unchecked")
                Holder<Biome>[] biomes = (Holder<Biome>[]) new Holder[TILE_WIDTH * TILE_WIDTH];
                heights = dhStyle.generate(origin[0], origin[1], spacing, TILE_WIDTH, biomes);
            } else {
                heights = fastTile(origin, spacing, 1).heights;
            }
            for (int i = 0; i < TILE_WIDTH; i += ACCURACY_STEP) {
                for (int j = 0; j < TILE_WIDTH; j += ACCURACY_STEP) {
                    int real = vanillaHeight(origin[0] + i * spacing, origin[1] + j * spacing);
                    errors[count++] = Math.abs(heights[i + j * TILE_WIDTH] - real);
                }
            }
        }
        return accuracy(errors);
    }

    public static Accuracy accuracy(int[] errors) {
        int[] sorted = errors.clone();
        Arrays.sort(sorted);
        long sum = 0;
        int withinTwo = 0;
        for (int error : sorted) {
            sum += error;
            if (error <= 2) {
                withinTwo++;
            }
        }
        return new Accuracy(sorted.length, (double) sum / sorted.length, sorted[(int) (sorted.length * 0.9)],
                (double) withinTwo / sorted.length);
    }

    private static int[][] origins(Random random, int count, int spacing) {
        int[][] origins = new int[count][];
        int tileBlocks = TILE_WIDTH * spacing;
        for (int i = 0; i < count; i++) {
            // Tiles are aligned to their own size, like LOD sections.
            origins[i] = new int[]{Math.floorDiv(random.nextInt(200_000) - 100_000, tileBlocks) * tileBlocks,
                    Math.floorDiv(random.nextInt(200_000) - 100_000, tileBlocks) * tileBlocks};
        }
        return origins;
    }

    private static long time(int[][] origins, IntConsumer task) {
        long start = System.nanoTime();
        for (int i = 0; i < origins.length; i++) {
            task.accept(i);
        }
        return System.nanoTime() - start;
    }

    private static double throughput(int[][] origins, int threads, IntConsumer task) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            long start = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < origins.length; i++) {
                int index = i;
                futures.add(pool.submit(() -> task.accept(index)));
            }
            for (Future<?> future : futures) {
                future.get();
            }
            return origins.length / ((System.nanoTime() - start) / 1e9);
        } catch (Exception e) {
            throw new IllegalStateException("Benchmark task failed", e);
        } finally {
            pool.shutdown();
        }
    }

    private static double millis(long nanos, int tiles) {
        return nanos / 1e6 / tiles;
    }
}
