package com.pockyl.dhturbo.client;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.generation.queues.IFullDataSourceRetrievalQueue;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.util.objects.RollingAverage;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.dh.LodTileWriter;
import com.pockyl.dhturbo.dh.WorkerPool;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Distant Horizons' LOD source of a multiplayer level, generating LODs on this client when the server shares its world
 * generation ({@link ClientGeneration}) and otherwise passing everything to DH's own queue that downloads LODs from a
 * server running Distant Horizons. Installed by {@code AbstractLodRequestStateMixin}.
 */
public final class ClientTurboQueue implements IFullDataSourceRetrievalQueue {
    private static final byte LARGEST_DETAIL = 12;
    private static final int MAX_LOGGED_ERRORS = 10;
    private static final long STATS_INTERVAL_NANOS = 10_000_000_000L;

    private final DhClientLevel level;
    private final IFullDataSourceRetrievalQueue remote;
    private final Map<Long, CompletableFuture<DataSourceRetrievalResult>> running = new ConcurrentHashMap<>();
    private final RollingAverage tileMillis = new RollingAverage(256);
    private final AtomicInteger loggedErrors = new AtomicInteger();
    private volatile boolean canRegenerate;
    private volatile long estimatedChunks;
    // Debug counters, logged every 10 s while requests come in.
    private final AtomicInteger requested = new AtomicInteger();
    private final AtomicInteger generated = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger delegated = new AtomicInteger();
    private final AtomicBoolean firstRequestLogged = new AtomicBoolean();
    private volatile long statsStart = System.nanoTime();

    public ClientTurboQueue(DhClientLevel level, IFullDataSourceRetrievalQueue remote) {
        this.level = level;
        this.remote = remote;
        if (DhTurbo.DEBUG) {
            DhTurbo.LOGGER.info("[client gen] Queue created for {}, generating on this client: {}", level, generating());
        }
    }

    private boolean generating() {
        return ClientGeneration.active(level);
    }

    @Override
    public byte lowestDataDetail() {
        return generating() ? LARGEST_DETAIL : remote.lowestDataDetail();
    }

    @Override
    public byte highestDataDetail() {
        return generating() ? 0 : remote.highestDataDetail();
    }

    @Override
    public String getRetrievalTypeName() {
        return generating() ? "generating LODs (DH Turbo)" : remote.getRetrievalTypeName();
    }

    @Override
    public void startAndSetTargetPos(DhBlockPos2D targetPos) {
        remote.startAndSetTargetPos(targetPos);
    }

    @Override
    public boolean getCanRegenerate() {
        return generating() ? canRegenerate : remote.getCanRegenerate();
    }

    @Override
    public void setCanRegenerate(boolean canRegenerate) {
        this.canRegenerate = canRegenerate;
        remote.setCanRegenerate(canRegenerate);
    }

    @Override
    public void removeRetrievalRequestIf(DhSectionPos.IPrimitiveLongConsumer removeIf) {
        // Our tiles start right away and take milliseconds; there is no waiting list to prune.
        remote.removeRetrievalRequestIf(removeIf);
    }

    @Override
    public boolean requestPosExistsWhere(DhSectionPos.IPrimitiveLongConsumer returnTrueIf) {
        for (long pos : running.keySet()) {
            if (returnTrueIf.accept(pos)) {
                return true;
            }
        }
        return remote.requestPosExistsWhere(returnTrueIf);
    }

    @Override
    public CompletableFuture<DataSourceRetrievalResult> submitRetrievalTask(long pos, byte requiredDataDetail) {
        LodTileWriter writer = ClientGeneration.writer(level);
        if (DhTurbo.DEBUG) {
            countRequest(pos, writer != null);
        }
        if (writer == null) {
            return remote.submitRetrievalTask(pos, requiredDataDetail);
        }
        CompletableFuture<DataSourceRetrievalResult> existing = running.get(pos);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<DataSourceRetrievalResult> future = generate(writer, pos);
        existing = running.putIfAbsent(pos, future);
        if (existing != null) {
            future.cancel(false);
            return existing;
        }
        // Attached after the insertion: a tile that is already done removes itself right here, not inside a map update.
        future.whenComplete((result, error) -> running.remove(pos, future));
        return future;
    }

    private void countRequest(long pos, boolean generate) {
        requested.incrementAndGet();
        if (firstRequestLogged.compareAndSet(false, true)) {
            DhTurbo.LOGGER.info("[client gen] First LOD request from Distant Horizons for {}: {}, {}", level,
                    DhSectionPos.toString(pos), generate ? "generating it here" : "passed to Distant Horizons' download queue");
        }
        if (!generate) {
            delegated.incrementAndGet();
        }
        long now = System.nanoTime();
        long start = statsStart;
        if (now - start >= STATS_INTERVAL_NANOS && statsStart == start) {
            statsStart = now;
            DhTurbo.LOGGER.info("[client gen] {} last 10 s: {} requests ({} passed to download), {} tiles generated, {} failed, "
                    + "{} running, {} ms per tile", level, requested.getAndSet(0), delegated.getAndSet(0), generated.getAndSet(0),
                    failed.getAndSet(0), running.size(), String.format("%.1f", tileMillis.getAverage()));
        }
    }

    /** Same data source setup as DH's own world generation queue for API data sources. */
    private CompletableFuture<DataSourceRetrievalResult> generate(LodTileWriter writer, long pos) {
        FullDataSourceV2 dataSource = FullDataSourceV2.createEmpty(pos);
        dataSource.setRunApiSetterValidation(false);
        byte sectionDetail = DhSectionPos.getDetailLevel(pos);
        dataSource.applyToChildren = sectionDetail > DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL;
        dataSource.applyToParent = sectionDetail < FullDataSourceProviderV2.ROOT_SECTION_DETAIL_LEVEL;
        int spacing = 1 << (sectionDetail - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            try {
                writer.fill(DhSectionPos.getMinCornerBlockX(pos), DhSectionPos.getMinCornerBlockZ(pos), spacing, dataSource);
            } catch (RuntimeException e) {
                failed.incrementAndGet();
                if (loggedErrors.incrementAndGet() <= MAX_LOGGED_ERRORS) {
                    DhTurbo.LOGGER.error("Failed to generate LOD tile {} on the client", DhSectionPos.toString(pos), e);
                }
                throw e;
            }
            tileMillis.add((System.nanoTime() - start) / 1e6);
            generated.incrementAndGet();
            return DataSourceRetrievalResult.CreateSuccess(pos, dataSource);
        }, WorkerPool.get());
    }

    @Override
    public CompletableFuture<Void> startClosingAsync(boolean cancelCurrentGeneration, boolean alsoInterruptRunning) {
        if (cancelCurrentGeneration) {
            running.values().forEach(future -> future.cancel(alsoInterruptRunning));
        }
        return remote.startClosingAsync(cancelCurrentGeneration, alsoInterruptRunning);
    }

    @Override
    public void close() {
        running.values().forEach(future -> future.cancel(true));
        remote.close();
    }

    @Override
    public int getWaitingTaskCount() {
        return generating() ? 0 : remote.getWaitingTaskCount();
    }

    @Override
    public int getInProgressTaskCount() {
        return generating() ? running.size() : remote.getInProgressTaskCount();
    }

    @Override
    public int getQueuedChunkCount() {
        return generating() ? 0 : remote.getQueuedChunkCount();
    }

    @Override
    public long getRetrievalEstimatedRemainingChunkCount() {
        return generating() ? estimatedChunks : remote.getRetrievalEstimatedRemainingChunkCount();
    }

    @Override
    public void setRetrievalEstimatedRemainingChunkCount(long newEstimate) {
        estimatedChunks = newEstimate;
        remote.setRetrievalEstimatedRemainingChunkCount(newEstimate);
    }

    @Override
    public void addDebugMenuStringsToList(List<String> messageList) {
        if (generating()) {
            messageList.add("DH Turbo client generation: " + running.size() + " tiles running");
        } else {
            remote.addDebugMenuStringsToList(messageList);
        }
    }

    @Override
    public RollingAverage getRollingAverageChunkGenTimeInMs() {
        return generating() ? tileMillis : remote.getRollingAverageChunkGenTimeInMs();
    }
}
