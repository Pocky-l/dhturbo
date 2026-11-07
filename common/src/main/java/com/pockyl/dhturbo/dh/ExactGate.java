package com.pockyl.dhturbo.dh;

import net.minecraft.util.Mth;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Whether the exact pass may start a batch now, and which batches a radius reaches. Real generation takes seconds per
 * batch, so it keeps out of the way of a busy server and of players on the move, whose surroundings change faster than
 * it can follow. No Distant Horizons classes, so game tests can reach it.
 */
public final class ExactGate {
    public static final String SERVER_BUSY = "server busy";
    public static final String PLAYER_MOVING = "player moving";
    /** Ticks may take this long on average; a tick has 50 ms. */
    private static final float MAX_TICK_MILLIS = 35;
    /** Blocks per second: above sprinting, below flying and riding fast. */
    private static final double MAX_SPEED = 10;
    /** How long after the last fast move the pass stays away. */
    private static final long SETTLE_NANOS = TimeUnit.SECONDS.toNanos(3);

    private List<int[]> lastPositions = List.of();
    private long lastCheck;
    private long lastFastMove = Long.MIN_VALUE;

    /**
     * Call on every poll, so movement is tracked between batches too.
     *
     * @param tickMillis smoothed time of a server tick
     * @param positions  block X and Z of the players of the level, in the level's order
     * @return why the pass has to wait, or null when it may run
     */
    @Nullable
    public String hold(float tickMillis, List<int[]> positions, long nanoTime) {
        if (fastMove(positions, nanoTime)) {
            lastFastMove = nanoTime;
        }
        lastPositions = positions;
        lastCheck = nanoTime;
        if (lastFastMove != Long.MIN_VALUE && nanoTime - lastFastMove < SETTLE_NANOS) {
            return PLAYER_MOVING;
        }
        return tickMillis > MAX_TICK_MILLIS ? SERVER_BUSY : null;
    }

    private boolean fastMove(List<int[]> positions, long nanoTime) {
        // A changed player list says nothing about speed: indices no longer belong to the same players.
        if (positions.size() != lastPositions.size() || nanoTime <= lastCheck) {
            return false;
        }
        double limit = MAX_SPEED * (nanoTime - lastCheck) / TimeUnit.SECONDS.toNanos(1);
        for (int i = 0; i < positions.size(); i++) {
            double dx = positions.get(i)[0] - lastPositions.get(i)[0];
            double dz = positions.get(i)[1] - lastPositions.get(i)[1];
            if (dx * dx + dz * dz > limit * limit) {
                return true;
            }
        }
        return false;
    }

    /** Whether the nearest chunk of a batch lies within the radius of a player's chunk. */
    public static boolean reaches(int chunkX, int chunkZ, int batchX, int batchZ, int batchChunks, int radius) {
        long dx = Mth.clamp(chunkX, batchX * batchChunks, batchX * batchChunks + batchChunks - 1) - chunkX;
        long dz = Mth.clamp(chunkZ, batchZ * batchChunks, batchZ * batchChunks + batchChunks - 1) - chunkZ;
        return dx * dx + dz * dz <= (long) radius * radius;
    }
}
