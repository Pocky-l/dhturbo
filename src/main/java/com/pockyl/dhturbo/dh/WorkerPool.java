package com.pockyl.dhturbo.dh;

import com.pockyl.dhturbo.Config;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Threads of the generator. A fork-join pool, so a tile task can split itself into bands and wait for them without
 * blocking a worker. Created on first use and kept for the whole game session (daemon threads).
 */
public final class WorkerPool {
    private static volatile ForkJoinPool pool;
    private static volatile ForkJoinPool background;

    private WorkerPool() {
    }

    public static ForkJoinPool get() {
        ForkJoinPool current = pool;
        if (current == null) {
            synchronized (WorkerPool.class) {
                current = pool;
                if (current == null) {
                    current = create(Config.threadCount());
                    pool = current;
                }
            }
        }
        return current;
    }

    /** Threads for work nobody waits for (exact terrain near the player): half of the workers, lowest priority. */
    public static ForkJoinPool background() {
        ForkJoinPool current = background;
        if (current == null) {
            synchronized (WorkerPool.class) {
                current = background;
                if (current == null) {
                    current = create(Math.max(1, Config.threadCount() / 2), "DH Turbo Background-", Thread.MIN_PRIORITY);
                    background = current;
                }
            }
        }
        return current;
    }

    private static ForkJoinPool create(int threads) {
        // Below the render and server threads: distant terrain may wait, the game must not stutter.
        return create(threads, "DH Turbo Worker-", Thread.NORM_PRIORITY - 2);
    }

    private static ForkJoinPool create(int threads, String name, int priority) {
        AtomicInteger counter = new AtomicInteger();
        return new ForkJoinPool(threads, forkJoinPool -> {
            ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(forkJoinPool);
            thread.setName(name + counter.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(priority);
            return thread;
        }, null, false);
    }
}
