package com.pockyl.dhturbo.client;

import com.seibel.distanthorizons.core.pos.DhSectionPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import com.pockyl.dhturbo.DhTurbo;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Diagnostics: LOD render sections rebuilt with far less geometry than they had before (terrain that vanished from
 * the screen), logged every 10 s with detail level and distance. Called from DH threads through
 * {@code LodRenderSectionMixin}.
 */
public final class RenderDropStats {
    private static final long INTERVAL_NANOS = 10_000_000_000L;
    private static final int MAX_TRACKED = 200_000;
    private static final int MAX_EXAMPLES = 5;
    /** Sections with fewer quads than this are too small to judge. */
    private static final int MIN_QUADS = 50;

    private static final Map<Long, Integer> LAST_QUADS = new ConcurrentHashMap<>();
    private static final LongAdder BUILDS = new LongAdder();
    private static final LongAdder REBUILDS = new LongAdder();
    private static final LongAdder DROPS = new LongAdder();
    private static final LongAdder EMPTIED = new LongAdder();
    private static final LongAdder BUILT_EMPTY = new LongAdder();
    private static final ConcurrentLinkedQueue<String> EXAMPLES = new ConcurrentLinkedQueue<>();
    private static final AtomicLong NEXT_REPORT = new AtomicLong(System.nanoTime() + INTERVAL_NANOS);

    private RenderDropStats() {
    }

    public static void built(long pos, int quads) {
        BUILDS.increment();
        if (quads == 0) {
            BUILT_EMPTY.increment();
        }
        Integer previous = LAST_QUADS.size() < MAX_TRACKED ? LAST_QUADS.put(pos, quads) : LAST_QUADS.get(pos);
        if (previous != null) {
            REBUILDS.increment();
            if (previous >= MIN_QUADS && quads < previous / 2) {
                DROPS.increment();
                if (quads == 0) {
                    EMPTIED.increment();
                }
                if (EXAMPLES.size() < MAX_EXAMPLES) {
                    EXAMPLES.add(String.format(Locale.ROOT, "d%d ~%d blocks away: %d -> %d quads",
                            DhSectionPos.getDetailLevel(pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL, distance(pos),
                            previous, quads));
                }
            }
        }
        report();
    }

    private static int distance(long pos) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return -1;
        }
        double dx = DhSectionPos.getCenterBlockPosX(pos) - player.getX();
        double dz = DhSectionPos.getCenterBlockPosZ(pos) - player.getZ();
        return (int) Math.sqrt(dx * dx + dz * dz);
    }

    private static void report() {
        long now = System.nanoTime();
        long due = NEXT_REPORT.get();
        if (now < due || !NEXT_REPORT.compareAndSet(due, now + INTERVAL_NANOS)) {
            return;
        }
        StringBuilder examples = new StringBuilder();
        String example;
        while ((example = EXAMPLES.poll()) != null) {
            examples.append("\n    ").append(example);
        }
        DhTurbo.LOGGER.info("[render] last 10 s: {} section builds ({} empty), {} rebuilds, {} lost over half their terrain ({} to nothing){}",
                BUILDS.sumThenReset(), BUILT_EMPTY.sumThenReset(), REBUILDS.sumThenReset(), DROPS.sumThenReset(), EMPTIED.sumThenReset(),
                examples);
    }
}
