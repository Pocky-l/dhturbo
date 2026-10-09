package com.pockyl.fatamorgana.dh;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import com.pockyl.fatamorgana.Fatamorgana;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * What Distant Horizons asks the generator for and how long it takes, logged every few seconds while requests come
 * in: requests per detail level, repeated requests for the same tile, distance from the player, time per tile.
 */
final class GeneratorStats {
    private static final long INTERVAL_NANOS = 10_000_000_000L;
    private static final int LEVELS = 13;
    private static final int MAX_TRACKED_TILES = 1_000_000;

    private final ServerLevel level;
    private final String name;
    private final LongAdder[] requests = adders();
    private final LongAdder[] repeats = adders();
    private final LongAdder[] distanceSum = adders();
    private final LongAdder nanos = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final Set<Long> seen = ConcurrentHashMap.newKeySet();
    private final AtomicLong nextReport = new AtomicLong(System.nanoTime() + INTERVAL_NANOS);
    private volatile long total;

    GeneratorStats(ServerLevel level, String name) {
        this.level = level;
        this.name = name;
    }

    void request(int detail, int lodX, int lodZ, int centerX, int centerZ) {
        int index = Math.min(detail, LEVELS - 1);
        requests[index].increment();
        long key = ((long) detail << 56) ^ (((long) lodX & 0xFFFFFFFL) << 28) ^ ((long) lodZ & 0xFFFFFFFL);
        if (seen.size() < MAX_TRACKED_TILES && !seen.add(key)) {
            repeats[index].increment();
        }
        distanceSum[index].add(distanceToPlayer(centerX, centerZ));
    }

    void done(long tileNanos) {
        nanos.add(tileNanos);
        report();
    }

    void error() {
        errors.increment();
    }

    private void report() {
        long now = System.nanoTime();
        long due = nextReport.get();
        if (now < due || !nextReport.compareAndSet(due, now + INTERVAL_NANOS)) {
            return;
        }
        StringBuilder levels = new StringBuilder();
        long count = 0;
        for (int i = 0; i < LEVELS; i++) {
            long n = requests[i].sumThenReset();
            long repeated = repeats[i].sumThenReset();
            long distance = distanceSum[i].sumThenReset();
            if (n == 0) {
                continue;
            }
            count += n;
            levels.append(String.format(Locale.ROOT, " d%d: %d (%d repeated, ~%d blocks away);", i, n, repeated,
                    distance / n));
        }
        long tileNanos = nanos.sumThenReset();
        total += count;
        Fatamorgana.LOGGER.info(String.format(Locale.ROOT,
                "[stats %s] last 10 s: %d tiles, %.1f ms/tile, %d errors, %d total |%s", name, count,
                count == 0 ? 0 : tileNanos / 1e6 / count, errors.sumThenReset(), total, levels));
    }

    private int distanceToPlayer(int x, int z) {
        try {
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) {
                return -1;
            }
            ServerPlayer player = players.get(0);
            return (int) Math.sqrt((player.getX() - x) * (player.getX() - x) + (player.getZ() - z) * (player.getZ() - z));
        } catch (RuntimeException e) {
            // The player list belongs to the server thread; a racy read is fine for statistics.
            return -1;
        }
    }

    private static LongAdder[] adders() {
        LongAdder[] adders = new LongAdder[LEVELS];
        for (int i = 0; i < LEVELS; i++) {
            adders[i] = new LongAdder();
        }
        return adders;
    }
}
