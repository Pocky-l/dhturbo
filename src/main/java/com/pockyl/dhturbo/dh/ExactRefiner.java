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
import com.pockyl.dhturbo.surface.SavedChunks;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

/**
 * Second pass near the players: while the generator has nothing to do and {@link ExactGate} lets it, the fast tiles
 * around them are replaced with the real generator's chunks ({@link ExactChunks}), nearest first. They reach Distant
 * Horizons through its API for
 * real chunks, so it lights and stores them like chunks a player loaded, and they count as finished across sessions.
 * Chunks Distant Horizons already holds as real (a player has been there, maybe built there) are left alone, and chunks
 * the world has saved reach it as they are ({@link SavedChunks}), first of all and out to the LOD distance.
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
    private final SavedChunks saved;
    private final LongSet finished = new LongOpenHashSet();
    private final LongSet savedRead = new LongOpenHashSet();
    private final LongAdder refined = new LongAdder();
    private final ExactGate gate = new ExactGate();
    private final Thread thread;
    private volatile boolean closed;
    private volatile boolean forgetProgress;

    /**
     * @param players  block X and Z of the players of this level, read from this refiner's thread
     * @param realData whether Distant Horizons holds real data for a chunk
     */
    ExactRefiner(ServerLevel level, IDhApiLevelWrapper wrapper, ExactChunks chunks, SavedChunks saved,
                 Supplier<List<int[]>> players, BiPredicate<Integer, Integer> realData) {
        this.level = level;
        this.wrapper = wrapper;
        this.chunks = chunks;
        this.saved = saved;
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
        // Distant Horizons closes generators only of levels that generate, so a stopped server ends this thread too.
        while (!closed && level.getServer().isRunning()) {
            try {
                Thread.sleep(POLL_MILLIS);
                List<int[]> positions = players.get();
                String hold = gate.hold(level.getServer().getCurrentSmoothedTickTime(), positions, System.nanoTime());
                if (forgetProgress) {
                    forgetProgress = false;
                    finished.clear();
                    savedRead.clear();
                    saved.forget();
                }
                if (!Config.ENABLED.get() || !DhIntegration.generationWanted()) {
                    report(positions, null);
                } else if (hold != null) {
                    report(positions, hold);
                } else {
                    nextBatch(positions);
                }
            } catch (InterruptedException e) {
                return;
            } catch (ExecutionException | RuntimeException | LinkageError e) {
                // Real generation is the world's own code: a mod that cannot run on our chunks stops this pass only.
                DhTurbo.LOGGER.warn("Exact terrain near the player stopped for {}; the fast tiles stay",
                        level.dimension().location(), e);
                TurboActivity.exactStatus("stopped, see the log");
                return;
            }
        }
    }

    /**
     * Saved chunks come first and do not wait for tiles: they are what the world really looks like, and cheap next to
     * generating. Filling the horizon takes minutes, a player's base should not.
     */
    private void nextBatch(List<int[]> positions) throws InterruptedException, ExecutionException {
        long batch = nearestBatch(DhIntegration.lodRadiusChunks(), positions, savedRead, this::unreadSaved);
        if (batch != Long.MAX_VALUE) {
            report(positions, "saved chunks");
            readSaved(ChunkPos.getX(batch), ChunkPos.getZ(batch));
            return;
        }
        int radius = Config.exactRadius(level.getServer().isDedicatedServer());
        if (radius <= 0 || !Config.generates(level.dimension())) {
            report(positions, null);
            return;
        }
        if (!idle()) {
            report(positions, "tiles first");
            return;
        }
        batch = nearestBatch(radius, positions, finished, this::fastTiles);
        if (batch == Long.MAX_VALUE) {
            report(positions, "done");
        } else {
            report(positions, "working");
            refine(ChunkPos.getX(batch), ChunkPos.getZ(batch));
        }
    }

    /** The next pass starts over: Distant Horizons lost its data. */
    void forgetProgress() {
        forgetProgress = true;
    }

    /** For the F3 line, from the level the players are in. */
    private static void report(List<int[]> positions, @Nullable String status) {
        if (!positions.isEmpty()) {
            TurboActivity.exactStatus(status);
        }
    }

    private static boolean idle() {
        return !HorizonFill.filling() && TurboActivity.idleNanos() >= IDLE_NANOS && WorkerPool.get().getActiveThreadCount() == 0
                && !WorkerPool.get().hasQueuedSubmissions();
    }

    /**
     * The nearest batch within the radius of a player that is wanted, or {@code Long.MAX_VALUE}.
     *
     * @param done batches not to look at again; unwanted ones are added
     */
    private long nearestBatch(int radius, List<int[]> positions, LongSet done, LongPredicate wanted) {
        long best = Long.MAX_VALUE;
        long bestDistance = Long.MAX_VALUE;
        int reach = radius / BATCH_CHUNKS + 1;
        for (int[] player : positions) {
            int chunkX = player[0] >> 4;
            int chunkZ = player[1] >> 4;
            int centerX = Math.floorDiv(chunkX, BATCH_CHUNKS);
            int centerZ = Math.floorDiv(chunkZ, BATCH_CHUNKS);
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dx = -reach; dx <= reach; dx++) {
                    long distance = (long) dx * dx + (long) dz * dz;
                    long key = ChunkPos.asLong(centerX + dx, centerZ + dz);
                    if (distance >= bestDistance || done.contains(key)
                            || !ExactGate.reaches(chunkX, chunkZ, centerX + dx, centerZ + dz, BATCH_CHUNKS, radius)) {
                        continue;
                    }
                    if (wanted.test(key)) {
                        best = key;
                        bestDistance = distance;
                    } else {
                        done.add(key);
                    }
                }
            }
        }
        return best;
    }

    /** Whether a batch holds chunks Distant Horizons does not have and the real generator may draw. */
    private boolean fastTiles(long batch) {
        return anyChunk(batch, (x, z) -> !realData.test(x, z) && !saved.exists(x, z));
    }

    /** Whether a batch holds saved chunks Distant Horizons does not have yet. */
    private boolean unreadSaved(long batch) {
        return anyChunk(batch, (x, z) -> saved.exists(x, z) && !realData.test(x, z));
    }

    private static boolean anyChunk(long batch, BiPredicate<Integer, Integer> test) {
        int minX = ChunkPos.getX(batch) * BATCH_CHUNKS;
        int minZ = ChunkPos.getZ(batch) * BATCH_CHUNKS;
        for (int z = 0; z < BATCH_CHUNKS; z++) {
            for (int x = 0; x < BATCH_CHUNKS; x++) {
                if (test.test(minX + x, minZ + z)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Hands the saved chunks of a batch to Distant Horizons as they are. */
    private void readSaved(int batchX, int batchZ) throws InterruptedException {
        savedRead.add(ChunkPos.asLong(batchX, batchZ));
        List<ChunkAccess> found = new ArrayList<>();
        for (int z = 0; z < BATCH_CHUNKS; z++) {
            for (int x = 0; x < BATCH_CHUNKS; x++) {
                int chunkX = batchX * BATCH_CHUNKS + x;
                int chunkZ = batchZ * BATCH_CHUNKS + z;
                if (saved.exists(chunkX, chunkZ) && !realData.test(chunkX, chunkZ)) {
                    saved.load(chunkX, chunkZ).ifPresent(found::add);
                }
            }
        }
        hand(found);
        TurboActivity.savedChunks(found.size());
        if (DhTurbo.DEBUG) {
            DhTurbo.LOGGER.info("[saved {}] batch {} {}: {} chunks", level.dimension().location(), batchX, batchZ, found.size());
        }
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
        // What the world saved is what it looks like, whatever the generator says.
        generated.removeIf(chunk -> realData.test(chunk.getPos().x, chunk.getPos().z)
                || saved.exists(chunk.getPos().x, chunk.getPos().z));
        hand(generated);
        refined.add(generated.size());
        TurboActivity.exactChunks(generated.size());
        if (DhTurbo.DEBUG) {
            DhTurbo.LOGGER.info("[exact {}] batch {} {} done, {} chunks so far", level.dimension().location(), batchX, batchZ,
                    refined.sum());
        }
    }

    private void hand(List<ChunkAccess> found) throws InterruptedException {
        for (int from = 0; from < found.size(); from += HANDED_AT_ONCE) {
            List<ChunkAccess> part = found.subList(from, Math.min(found.size(), from + HANDED_AT_ONCE));
            for (ChunkAccess chunk : part) {
                DhApiResult<Void> result = DhApi.Delayed.terrainRepo.overwriteChunkDataAsync(wrapper,
                        new Object[]{chunk, level});
                if (!result.success) {
                    throw new IllegalStateException(result.message);
                }
            }
            awaitUpdate(part.get(part.size() - 1).getPos());
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
