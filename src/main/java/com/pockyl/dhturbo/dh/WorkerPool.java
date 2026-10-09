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

    private static ForkJoinPool create(int threads) {
        AtomicInteger counter = new AtomicInteger();
        return new ForkJoinPool(threads, forkJoinPool -> {
            ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(forkJoinPool);
            thread.setName("DH Turbo Worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            // Below the render and server threads: distant terrain may wait, the game must not stutter.
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            return thread;
        }, null, false);
    }
}
