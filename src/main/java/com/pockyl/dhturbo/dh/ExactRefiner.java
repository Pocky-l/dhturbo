package com.pockyl.dhturbo.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.surface.ExactChunks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * Second pass near the players: while the generator has nothing to do, the fast tiles around them are replaced with
 * the real generator's chunks ({@link ExactChunks}), nearest first. They reach Distant Horizons through its API for
 * real chunks, so it lights and stores them like chunks a player loaded, and they count as finished across sessions.
 * Chunks Distant Horizons already holds as real (a player has been there, maybe built there) are left alone.
 */
final class ExactRefiner implements AutoCloseable {
    private static final int BATCH_CHUNKS = ExactChunks.BATCH;
    /** The generator counts as idle this long after the last tile request. */
    private static final long IDLE_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long POLL_MILLIS = 500;
    /** Chunks handed to Distant Horizons at a time: its queue drops what does not fit. */
    private static final int HANDED_AT_ONCE = 16;
    private static final long UPDATE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final AtomicInteger THREADS = new AtomicInteger();

    private final ServerLevel level;
    private final IDhApiLevelWrapper wrapper;
    private final ExactChunks chunks;
    private final Supplier<List<int[]>> players;
    private final BiPredicate<Integer, Integer> realData;
    private final LongSet finished = new LongOpenHashSet();
    private final LongAdder refined = new LongAdder();
    private final Thread thread;
    private volatile boolean closed;

    /**
     * @param players  block X and Z of the players of this level, read from this refiner's thread
     * @param realData whether Distant Horizons holds real data for a chunk
     */
    ExactRefiner(ServerLevel level, IDhApiLevelWrapper wrapper, ExactChunks chunks, Supplier<List<int[]>> players,
                 BiPredicate<Integer, Integer> realData) {
        this.level = level;
        this.wrapper = wrapper;
        this.chunks = chunks;
        this.players = players;
        this.realData = realData;
        this.thread = new Thread(this::run, "DH Turbo Exact Terrain-" + THREADS.incrementAndGet());
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    IDhApiLevelWrapper wrapper() {
        return wrapper;
    }

    /** Chunks replaced with real generation so far. */
    long refined() {
        return refined.sum();
    }

    private void run() {
        while (!closed) {
            try {
                Thread.sleep(POLL_MILLIS);
                int radius = Config.EXACT_RADIUS.get();
                if (radius <= 0 || !Config.generates(level.dimension()) || !idle()) {
                    continue;
                }
                long batch = nearestBatch(radius);
                if (batch != Long.MAX_VALUE) {
                    refine(ChunkPos.getX(batch), ChunkPos.getZ(batch));
                }
            } catch (InterruptedException e) {
                return;
            } catch (ExecutionException | RuntimeException | LinkageError e) {
                // Real generation is the world's own code: a mod that cannot run on our chunks stops this pass only.
                DhTurbo.LOGGER.warn("Exact terrain near the player stopped for {}; the fast tiles stay",
                        level.dimension().location(), e);
                return;
            }
        }
    }

    private static boolean idle() {
        return TurboActivity.idleNanos() >= IDLE_NANOS && WorkerPool.get().getActiveThreadCount() == 0
                && !WorkerPool.get().hasQueuedSubmissions();
    }

    /** The nearest batch within the radius of a player that still has fast tiles, or {@code Long.MAX_VALUE}. */
    private long nearestBatch(int radius) {
        long best = Long.MAX_VALUE;
        long bestDistance = Long.MAX_VALUE;
        int reach = (radius + BATCH_CHUNKS - 1) / BATCH_CHUNKS;
        for (int[] player : players.get()) {
            int centerX = Math.floorDiv(player[0] >> 4, BATCH_CHUNKS);
            int centerZ = Math.floorDiv(player[1] >> 4, BATCH_CHUNKS);
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dx = -reach; dx <= reach; dx++) {
                    long distance = (long) dx * dx + (long) dz * dz;
                    long key = ChunkPos.asLong(centerX + dx, centerZ + dz);
                    if (distance >= bestDistance || finished.contains(key)) {
                        continue;
                    }
                    if (allReal(centerX + dx, centerZ + dz)) {
                        finished.add(key);
                    } else {
                        best = key;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private boolean allReal(int batchX, int batchZ) {
        for (int z = 0; z < BATCH_CHUNKS; z++) {
            for (int x = 0; x < BATCH_CHUNKS; x++) {
                if (!realData.test(batchX * BATCH_CHUNKS + x, batchZ * BATCH_CHUNKS + z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Generates a batch on the background workers and hands its chunks to Distant Horizons. */
    void refine(int batchX, int batchZ) throws InterruptedException, ExecutionException {
        List<ChunkAccess> generated = new ArrayList<>();
        WorkerPool.background().submit(() -> chunks.generate(batchX, batchZ, chunk -> {
            synchronized (generated) {
                generated.add(chunk);
            }
        })).get();
        finished.add(ChunkPos.asLong(batchX, batchZ));
        for (int from = 0; from < generated.size(); from += HANDED_AT_ONCE) {
            ChunkAccess last = null;
            for (ChunkAccess chunk : generated.subList(from, Math.min(generated.size(), from + HANDED_AT_ONCE))) {
                if (realData.test(chunk.getPos().x, chunk.getPos().z)) {
                    continue;
                }
                DhApiResult<Void> result = DhApi.Delayed.terrainRepo.overwriteChunkDataAsync(wrapper,
                        new Object[]{chunk, level});
                if (!result.success) {
                    throw new IllegalStateException(result.message);
                }
                refined.increment();
                last = chunk;
            }
            if (last != null) {
                awaitUpdate(last.getPos());
            }
        }
        if (DhTurbo.DEBUG) {
            DhTurbo.LOGGER.info("[exact {}] batch {} {} done, {} chunks so far", level.dimension().location(), batchX, batchZ,
                    refined.sum());
        }
    }

    private void awaitUpdate(ChunkPos pos) throws InterruptedException {
        long start = System.nanoTime();
        while (SharedApi.isChunkAtChunkPosAlreadyUpdating((ILevelWrapper) wrapper, pos.x, pos.z)
                && System.nanoTime() - start < UPDATE_TIMEOUT_NANOS) {
            Thread.sleep(20);
        }
    }

    @Override
    public void close() {
        closed = true;
        thread.interrupt();
    }
}
