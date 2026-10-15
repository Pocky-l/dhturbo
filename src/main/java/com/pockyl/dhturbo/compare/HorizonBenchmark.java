package com.pockyl.dhturbo.compare;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.dh.WorkerPool;
import com.pockyl.dhturbo.surface.ColumnBuilder;
import com.pockyl.dhturbo.surface.RealStructures;
import com.pockyl.dhturbo.surface.StructurePrefetch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.function.UnaryOperator;

/**
 * Developer tool: fills the horizon around a fresh spawn the way Distant Horizons asks for it, and reports how fast.
 * <p>
 * Tiles of 64 columns, finer near the player: detail {@code d} (columns {@code 2^d} blocks wide) out to
 * {@code 256 * 2^d} blocks. Requested nearest first, with as many tiles in flight as Distant Horizons allows (its thread
 * count + 1), on the generator's own worker pool. Each origin starts with empty caches, like a newly joined world.
 * <p>
 * {@code -Ddhturbo.compare.horizonRate=<tiles/s>} paces the requests like Distant Horizons in game (its saving and
 * meshing set the pace there), {@code -Ddhturbo.compare.prefetch=true} prepares structures in the idle time between.
 */
final class HorizonBenchmark {
    private static final int TILE_WIDTH = 64;
    private static final int NEAR_RADIUS = 256;

    private HorizonBenchmark() {
    }

    private record Tile(int minX, int minZ, int detail, double distance) {
    }

    static List<String> run(ServerLevel level, int radius, int origins) {
        List<String> report = new ArrayList<>();
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            report.add("The overworld does not use a noise generator");
            return report;
        }
        int inFlight = Config.threadCount() + 1;
        int rate = Integer.getInteger("dhturbo.compare.horizonRate", 0);
        boolean prefetch = Boolean.getBoolean("dhturbo.compare.prefetch");
        report.add(String.format(Locale.ROOT,
                "Horizon fill: radius %d blocks, %d worker threads, %d tiles in flight, %s, structure prefetch %s",
                radius, Config.threadCount(), inFlight, rate > 0 ? "at most " + rate + " tiles/s" : "no pacing",
                prefetch ? "on" : "off"));
        Random random = new Random(42);
        for (int o = 0; o < origins; o++) {
            int originX = random.nextInt(20_000) - 10_000;
            int originZ = random.nextInt(20_000) - 10_000;
            ColumnBuilder builder = new ColumnBuilder(generator, level.registryAccess(), level.dimensionType(),
                    level.getSeed(), UnaryOperator.identity(), level.getStructureManager());
            List<Tile> tiles = tiles(originX, originZ, radius);
            RealStructures structures = builder.realStructures();
            StructurePrefetch prefetcher = prefetch && structures != null ? new StructurePrefetch(structures,
                    () -> List.of(new int[]{originX, originZ}), WorkerPool.get()) : null;
            try {
                report.addAll(fill(builder, tiles, inFlight, rate, prefetcher, originX, originZ));
            } finally {
                if (prefetcher != null) {
                    prefetcher.close();
                }
            }
        }
        return report;
    }

    /** Tiles around the origin by distance band, nearest first. */
    private static List<Tile> tiles(int originX, int originZ, int radius) {
        List<Tile> tiles = new ArrayList<>();
        int inner = 0;
        for (int detail = 0; ; detail++) {
            int outer = Math.min(radius, NEAR_RADIUS << detail);
            int width = TILE_WIDTH << detail;
            int from = Math.floorDiv(-outer, width) - 1;
            int to = Math.floorDiv(outer, width) + 1;
            int baseX = Math.floorDiv(originX, width) * width;
            int baseZ = Math.floorDiv(originZ, width) * width;
            for (int tx = from; tx <= to; tx++) {
                for (int tz = from; tz <= to; tz++) {
                    int minX = baseX + tx * width;
                    int minZ = baseZ + tz * width;
                    double distance = Math.hypot(minX + width / 2.0 - originX, minZ + width / 2.0 - originZ);
                    if (distance >= inner && distance < outer) {
                        tiles.add(new Tile(minX, minZ, detail, distance));
                    }
                }
            }
            inner = outer;
            if (outer >= radius) {
                break;
            }
        }
        tiles.sort(Comparator.comparingDouble(Tile::distance));
        return tiles;
    }

    private static List<String> fill(ColumnBuilder builder, List<Tile> tiles, int inFlight, int rate,
                                     StructurePrefetch prefetcher, int originX, int originZ) {
        Semaphore slots = new Semaphore(inFlight);
        long start = System.nanoTime();
        double[] doneAt = new double[tiles.size()];
        double[] latency = new double[tiles.size()];
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int t = 0; t < tiles.size(); t++) {
            Tile tile = tiles.get(t);
            if (rate > 0) {
                long due = start + t * 1_000_000_000L / rate;
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    java.util.concurrent.locks.LockSupport.parkNanos(wait);
                }
            }
            slots.acquireUninterruptibly();
            if (prefetcher != null) {
                prefetcher.requested(tile.distance, 1 << tile.detail);
                if (tile.detail <= 4) {
                    int size = TILE_WIDTH << tile.detail;
                    prefetcher.covered(tile.minX, tile.minZ, tile.minX + size - 1, tile.minZ + size - 1);
                }
            }
            int index = t;
            futures.add(CompletableFuture.runAsync(() -> {
                long begin = System.nanoTime();
                try {
                    builder.build(tile.minX, tile.minZ, 1 << tile.detail, TILE_WIDTH, (i, j, biome, layers) -> {
                    });
                } finally {
                    long end = System.nanoTime();
                    latency[index] = (end - begin) / 1e6;
                    doneAt[index] = (end - start) / 1e6;
                    slots.release();
                }
            }, WorkerPool.get()));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        double total = (System.nanoTime() - start) / 1e6;
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "Origin %d %d: %d tiles in %.1f s (%.0f tiles/s)", originX, originZ,
                tiles.size(), total / 1000, tiles.size() / (total / 1000)));
        double[] sortedDone = doneAt.clone();
        Arrays.sort(sortedDone);
        lines.add(String.format(Locale.ROOT, "  horizon filled: 50%% at %.1f s, 90%% at %.1f s, 100%% at %.1f s",
                sortedDone[sortedDone.length / 2] / 1000, sortedDone[sortedDone.length * 9 / 10] / 1000,
                sortedDone[sortedDone.length - 1] / 1000));
        int maxDetail = tiles.stream().mapToInt(Tile::detail).max().orElse(0);
        for (int detail = 0; detail <= maxDetail; detail++) {
            List<Double> values = new ArrayList<>();
            double lastDone = 0;
            for (int t = 0; t < tiles.size(); t++) {
                if (tiles.get(t).detail == detail) {
                    values.add(latency[t]);
                    lastDone = Math.max(lastDone, doneAt[t]);
                }
            }
            if (values.isEmpty()) {
                continue;
            }
            values.sort(null);
            lines.add(String.format(Locale.ROOT,
                    "  detail %d: %d tiles, ms per tile p50 %.0f, p95 %.0f, max %.0f; last done at %.1f s", detail,
                    values.size(), values.get(values.size() / 2), values.get(values.size() * 95 / 100),
                    values.getLast(), lastDone / 1000));
        }
        if (builder.realStructures() != null) {
            lines.add("  structures: " + builder.realStructures().timings());
        }
        if (prefetcher != null) {
            lines.add("  prefetched areas: " + prefetcher.areas());
        }
        return lines;
    }
}
