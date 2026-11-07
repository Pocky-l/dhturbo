package com.pockyl.dhturbo.surface;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/** Time spent in each stage of building tiles, for the measuring tools. Negligible next to the stages themselves. */
public final class BuildProfile {
    /** Stages of a tile, in the order they run. */
    public enum Stage {
        LATTICE, CORNERS, STRUCTURES, TOPS, TREES, LAKES, COLUMNS, SINK,
        EXACT_STARTS, EXACT_BIOMES, EXACT_NOISE, EXACT_SURFACE, EXACT_CARVERS, EXACT_FEATURES, EXACT_COLUMNS
    }

    private static final LongAdder[] NANOS = new LongAdder[Stage.values().length];
    private static final LongAdder TILES = new LongAdder();
    private static final LongAdder EXACT_CHUNKS = new LongAdder();

    static {
        for (int i = 0; i < NANOS.length; i++) {
            NANOS[i] = new LongAdder();
        }
    }

    private BuildProfile() {
    }

    /** Adds the time since {@code begin} to the stage and returns the current time, to chain stages. */
    public static long lap(Stage stage, long begin) {
        long now = System.nanoTime();
        NANOS[stage.ordinal()].add(now - begin);
        return now;
    }

    public static void tile() {
        TILES.increment();
    }

    public static void exactChunk() {
        EXACT_CHUNKS.increment();
    }

    /** Clears the counters. */
    public static void reset() {
        for (LongAdder nanos : NANOS) {
            nanos.reset();
        }
        TILES.reset();
        EXACT_CHUNKS.reset();
    }

    /** One line with the milliseconds per tile of each stage that ran, and per chunk of the exact stages. */
    public static String report() {
        long tiles = Math.max(1, TILES.sum());
        long chunks = Math.max(1, EXACT_CHUNKS.sum());
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%d tiles:", TILES.sum()));
        double total = 0;
        for (Stage stage : Stage.values()) {
            long nanos = NANOS[stage.ordinal()].sum();
            if (nanos > 0 && !stage.name().startsWith("EXACT")) {
                double millis = nanos / 1e6 / tiles;
                total += millis;
                line.append(String.format(Locale.ROOT, " %s %.1f", stage.name().toLowerCase(Locale.ROOT), millis));
            }
        }
        line.append(String.format(Locale.ROOT, " = %.1f ms/tile", total));
        if (EXACT_CHUNKS.sum() > 0) {
            double exact = 0;
            line.append(String.format(Locale.ROOT, " | exact %d chunks:", EXACT_CHUNKS.sum()));
            for (Stage stage : Stage.values()) {
                long nanos = NANOS[stage.ordinal()].sum();
                if (nanos > 0 && stage.name().startsWith("EXACT")) {
                    double millis = nanos / 1e6 / chunks;
                    exact += millis;
                    line.append(String.format(Locale.ROOT, " %s %.2f",
                            stage.name().substring(6).toLowerCase(Locale.ROOT), millis));
                }
            }
            line.append(String.format(Locale.ROOT, " = %.2f ms/emitted chunk", exact));
        }
        return line.toString();
    }
}
