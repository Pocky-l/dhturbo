package com.pockyl.dhturbo.dh;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * How busy the generator is in this game, for the F3 screen: tiles made since the game started, tiles per second,
 * time per tile and tiles in progress. Counts both the integrated server's generator and client-side generation on a
 * server; plain counters with no Distant Horizons classes, so the debug screen can read them safely.
 */
public final class TurboActivity {
    /** Weight of the newest second in the smoothed rate. */
    private static final double RATE_SMOOTHING = 0.3;
    private static final long SECOND = 1_000_000_000L;

    private static final LongAdder DONE = new LongAdder();
    private static final LongAdder FAILED = new LongAdder();
    private static final LongAdder NANOS = new LongAdder();
    private static final AtomicInteger RUNNING = new AtomicInteger();

    private static long rateCount;
    private static long rateTime;
    private static double rate;
    private static long msCount;
    private static long msNanos;
    private static double msPerTile;

    private TurboActivity() {
    }

    /** A tile started generating; returns the start time to pass to {@link #finished} or {@link #failed}. */
    public static long started() {
        RUNNING.incrementAndGet();
        return System.nanoTime();
    }

    public static void finished(long start) {
        RUNNING.decrementAndGet();
        NANOS.add(System.nanoTime() - start);
        DONE.increment();
    }

    public static void failed() {
        RUNNING.decrementAndGet();
        FAILED.increment();
    }

    /** The F3 line, or empty before the first tile. Called from the render thread only. */
    public static Optional<String> debugLine() {
        long done = DONE.sum();
        int running = Math.max(0, RUNNING.get());
        if (done == 0 && running == 0) {
            return Optional.empty();
        }
        long now = System.nanoTime();
        if (rateTime == 0) {
            rateTime = now;
            rateCount = done;
        } else if (now - rateTime >= SECOND) {
            double perSecond = (done - rateCount) * (double) SECOND / (now - rateTime);
            rate = rate == 0 ? perSecond : rate + (perSecond - rate) * RATE_SMOOTHING;
            long nanos = NANOS.sum();
            if (done > msCount) {
                msPerTile = (nanos - msNanos) / 1e6 / (done - msCount);
            }
            msCount = done;
            msNanos = nanos;
            rateCount = done;
            rateTime = now;
        }
        long failed = FAILED.sum();
        return Optional.of(String.format(Locale.ROOT, "DH Turbo: %d tiles, %.0f/s, %.1f ms/tile, %d running%s", done, rate,
                msPerTile, running, failed > 0 ? ", " + failed + " failed" : ""));
    }
}
