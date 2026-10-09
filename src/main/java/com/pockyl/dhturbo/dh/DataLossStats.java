package com.pockyl.dhturbo.dh;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import com.pockyl.dhturbo.DhTurbo;

import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Diagnostics: LOD data sections that lose terrain columns when DH merges new data into them, logged every 10 s
 * with where the new data came from (same detail level, a finer or a coarser section). Called from DH threads through
 * {@code FullDataSourceV2Mixin}.
 */
public final class DataLossStats {
    private static final long INTERVAL_NANOS = 10_000_000_000L;
    private static final String[] SOURCES = {"same level", "from finer", "from coarser"};
    private static final int MAX_EXAMPLES = 5;

    private static final LongAdder UPDATES = new LongAdder();
    private static final LongAdder[] LOSSES = {new LongAdder(), new LongAdder(), new LongAdder()};
    private static final LongAdder[] COLUMNS = {new LongAdder(), new LongAdder(), new LongAdder()};
    private static final ConcurrentLinkedQueue<String> EXAMPLES = new ConcurrentLinkedQueue<>();
    private static final AtomicLong NEXT_REPORT = new AtomicLong(System.nanoTime() + INTERVAL_NANOS);

    private DataLossStats() {
    }

    public static int filledColumns(FullDataSourceV2 source) {
        int filled = 0;
        for (LongArrayList column : source.dataPoints) {
            if (column != null && !column.isEmpty()) {
                filled++;
            }
        }
        return filled;
    }

    public static void update(FullDataSourceV2 target, FullDataSourceV2 input, int before, int after) {
        UPDATES.increment();
        if (after < before) {
            int targetDetail = DhSectionPos.getDetailLevel(target.getPos());
            int inputDetail = DhSectionPos.getDetailLevel(input.getPos());
            int source = inputDetail == targetDetail ? 0 : inputDetail < targetDetail ? 1 : 2;
            LOSSES[source].increment();
            COLUMNS[source].add(before - after);
            if (EXAMPLES.size() < MAX_EXAMPLES) {
                EXAMPLES.add(String.format(Locale.ROOT, "d%d at %d %d lost %d of %d columns (%s, input filled %d)",
                        targetDetail - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL,
                        DhSectionPos.getMinCornerBlockX(target.getPos()), DhSectionPos.getMinCornerBlockZ(target.getPos()),
                        before - after, before, SOURCES[source], filledColumns(input)));
            }
        }
        report();
    }

    private static void report() {
        long now = System.nanoTime();
        long due = NEXT_REPORT.get();
        if (now < due || !NEXT_REPORT.compareAndSet(due, now + INTERVAL_NANOS)) {
            return;
        }
        StringBuilder sources = new StringBuilder();
        for (int i = 0; i < SOURCES.length; i++) {
            sources.append(String.format(Locale.ROOT, " %s: %d updates, %d columns;", SOURCES[i],
                    LOSSES[i].sumThenReset(), COLUMNS[i].sumThenReset()));
        }
        StringBuilder examples = new StringBuilder();
        String example;
        while ((example = EXAMPLES.poll()) != null) {
            examples.append("\n    ").append(example);
        }
        DhTurbo.LOGGER.info("[data] last 10 s: {} merges, terrain lost in:{}{}", UPDATES.sumThenReset(), sources, examples);
    }
}
