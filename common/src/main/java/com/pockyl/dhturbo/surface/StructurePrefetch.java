package com.pockyl.dhturbo.surface;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.ChunkPos;

import com.pockyl.dhturbo.DhTurbo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Finds and builds the structures around the players before Distant Horizons asks for the tiles they stand on, while
 * the generator's workers have nothing else to do.
 * <p>
 * Structures are the expensive part of a distant tile (a village takes a few hundred milliseconds), and Distant
 * Horizons asks for tiles at its own pace, so the workers are idle a good part of the time. These threads spend that
 * idle time on the structures of areas 256 blocks wide, nearest to a player first, out to where tiles show structures.
 * Everything lands in {@link RealStructures}' caches, the same work a tile would do; a tile asking for a structure being
 * built here waits for it instead of building it twice, and areas tiles already covered are skipped.
 * <p>
 * Only while Distant Horizons is filling the horizon (a tile asked for in the last {@link #ACTIVE_NANOS}): once it is
 * done, nothing runs. Never while a worker is busy, on a third of the worker count, at the lowest thread priority.
 */
public final class StructurePrefetch implements AutoCloseable {
    private static final int AREA = 256;
    /** Prefetched before the first tile shows how far structures are asked for. */
    private static final int START_RADIUS = 1024;
    private static final int MAX_RADIUS = 8192;
    private static final int FORGET_AT = 65_536;
    private static final long IDLE_WAIT_MILLIS = 20;
    private static final long DONE_WAIT_MILLIS = 500;
    /** Prefetching stops this long after the last tile request: the horizon is complete. */
    private static final long ACTIVE_NANOS = 30_000_000_000L;
    private static final AtomicInteger THREADS = new AtomicInteger();
    /** Areas prefetched by every prefetcher of this game session, for the F3 line. */
    private static final LongAdder TOTAL = new LongAdder();

    private final RealStructures structures;
    private final Supplier<List<int[]>> players;
    private final ForkJoinPool workers;
    private final List<Thread> threads = new ArrayList<>();
    /** Areas taken by a prefetch thread (done or in progress); guarded by itself. */
    private final LongSet claimed = new LongOpenHashSet();
    private final LongAdder areas = new LongAdder();
    private final AtomicBoolean reported = new AtomicBoolean();
    /** Farthest tile with structures asked for, and nearest tile too coarse for them (MAX_VALUE: none yet). */
    private volatile double farthestWithStructures;
    private volatile double nearestWithout = Double.MAX_VALUE;
    private volatile long lastRequest = System.nanoTime() - ACTIVE_NANOS;
    private volatile boolean closed;

    /**
     * @param players block X and Z of every player the level's tiles are generated for; read from these threads, so it
     *                must tolerate concurrent changes
     * @param workers the generator's workers: prefetching pauses while any of them is busy, and uses a third of their
     *                count in threads
     */
    public StructurePrefetch(RealStructures structures, Supplier<List<int[]>> players, ForkJoinPool workers) {
        this.structures = structures;
        this.players = players;
        this.workers = workers;
        int count = Math.max(1, workers.getParallelism() / 3);
        for (int i = 0; i < count; i++) {
            Thread thread = new Thread(this::run, "DH Turbo Structure Prefetch-" + THREADS.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            threads.add(thread);
            thread.start();
        }
    }

    /**
     * A tile was asked for at this distance from the nearest player. Distant Horizons asks nearest first, so until the
     * first tile too coarse for structures, the radius keeps twice the distance reached; then it is the farthest tile
     * with structures.
     */
    public void requested(double distance, int spacing) {
        lastRequest = System.nanoTime();
        if (spacing <= RealStructures.MAX_SPACING) {
            if (distance > farthestWithStructures) {
                farthestWithStructures = distance;
            }
        } else if (distance < nearestWithout) {
            nearestWithout = distance;
        }
    }

    private int radius() {
        double reach = nearestWithout != Double.MAX_VALUE
                ? farthestWithStructures
                : Math.max(START_RADIUS, 2 * farthestWithStructures);
        return (int) Math.min(MAX_RADIUS, reach + AREA);
    }

    /**
     * A tile with structures was built over these blocks (inclusive): the areas inside it have their structures, so
     * they are not prefetched (again, if the caches dropped them since).
     */
    public void covered(int minX, int minZ, int maxX, int maxZ) {
        int fromX = Math.floorDiv(minX + AREA - 1, AREA);
        int fromZ = Math.floorDiv(minZ + AREA - 1, AREA);
        int toX = Math.floorDiv(maxX + 1, AREA) - 1;
        int toZ = Math.floorDiv(maxZ + 1, AREA) - 1;
        if (fromX > toX || fromZ > toZ) {
            return;
        }
        synchronized (claimed) {
            for (int x = fromX; x <= toX; x++) {
                for (int z = fromZ; z <= toZ; z++) {
                    claimed.add(ChunkPos.asLong(x, z));
                }
            }
        }
    }

    /** Areas prefetched so far. */
    public long areas() {
        return areas.sum();
    }

    /** Areas prefetched by all prefetchers so far. */
    public static long totalAreas() {
        return TOTAL.sum();
    }

    @Override
    public void close() {
        closed = true;
        threads.forEach(Thread::interrupt);
    }

    private void run() {
        while (!closed) {
            try {
                if (!idle()) {
                    Thread.sleep(IDLE_WAIT_MILLIS);
                    continue;
                }
                long area = claimNext();
                if (area == Long.MIN_VALUE) {
                    Thread.sleep(DONE_WAIT_MILLIS);
                    continue;
                }
                int minX = ChunkPos.getX(area) * AREA;
                int minZ = ChunkPos.getZ(area) * AREA;
                structures.around(minX, minZ, minX + AREA - 1, minZ + AREA - 1, false);
                areas.increment();
                TOTAL.increment();
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                if (reported.compareAndSet(false, true)) {
                    DhTurbo.LOGGER.warn("Prefetching structures failed; tiles build them when asked", e);
                }
                try {
                    Thread.sleep(DONE_WAIT_MILLIS);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    /** Whether Distant Horizons is filling the horizon and the workers are all idle right now. */
    private boolean idle() {
        return System.nanoTime() - lastRequest < ACTIVE_NANOS && !workers.hasQueuedSubmissions()
                && workers.getActiveThreadCount() == 0;
    }

    /** Claims the nearest area to any player within the radius, or returns MIN_VALUE when all are claimed. */
    private long claimNext() {
        List<int[]> positions = players.get();
        if (positions.isEmpty()) {
            return Long.MIN_VALUE;
        }
        int rings = radius() / AREA;
        synchronized (claimed) {
            if (claimed.size() > FORGET_AT) {
                // Players moved far: the caches have dropped most of these areas anyway.
                claimed.clear();
            }
            for (int ring = 0; ring <= rings; ring++) {
                for (int[] position : positions) {
                    int centerX = Math.floorDiv(position[0], AREA);
                    int centerZ = Math.floorDiv(position[1], AREA);
                    for (int offset = -ring; offset <= ring; offset++) {
                        long area = claim(centerX + offset, centerZ - ring, centerX + offset, centerZ + ring,
                                centerX - ring, centerZ + offset, centerX + ring, centerZ + offset);
                        if (area != Long.MIN_VALUE) {
                            return area;
                        }
                    }
                }
            }
        }
        return Long.MIN_VALUE;
    }

    private long claim(int... coordinates) {
        for (int i = 0; i < coordinates.length; i += 2) {
            long area = ChunkPos.asLong(coordinates[i], coordinates[i + 1]);
            if (claimed.add(area)) {
                return area;
            }
        }
        return Long.MIN_VALUE;
    }
}
