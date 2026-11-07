package com.pockyl.dhturbo.compare;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.platform.Platform;
import com.pockyl.dhturbo.dh.DhIntegration;
import com.pockyl.dhturbo.dh.TurboActivity;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Developer tool: times Distant Horizons' own pregeneration ({@code /dh pregen}) on a dedicated server, so the
 * generator is measured inside Distant Horizons' real pipeline (its task queue, saving, propagation) and can be
 * compared with Distant Horizons' own generators on the same world.
 * <p>
 * Started with {@code -Ddhturbo.pregen=<chunk radius>} ({@code -Ddhturbo.pregen.label=<text>} names the run): once the
 * server is up, it runs {@code /dh pregen start} around 0 0, waits until it is complete, appends wall time and the
 * process's CPU time to {@code dhturbo-pregen.txt} in the server directory and stops the server.
 */
public final class PregenBenchmark {
    private static final long WARM_UP_MILLIS = 10_000;
    /** Distant Horizons keeps new data in memory for up to 10 s before it can be read back. */
    private static final long EXACT_SETTLE_MILLIS = 15_000;
    private static final long POLL_MILLIS = 1_000;
    private static final long TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(60);

    private PregenBenchmark() {
    }

    public static void registerIfRequested() {
        if (System.getProperty(DhTurbo.MOD_ID + ".pregen") != null) {
            Platform.INSTANCE.onServerStarted(PregenBenchmark::onServerStarted);
        }
    }

    private static void onServerStarted(MinecraftServer server) {
        int radius = Integer.getInteger(DhTurbo.MOD_ID + ".pregen", 64);
        String label = System.getProperty(DhTurbo.MOD_ID + ".pregen.label", "run");
        Thread thread = new Thread(() -> run(server, radius, label), "DH Turbo pregen benchmark");
        thread.setDaemon(true);
        thread.start();
    }

    private static void run(MinecraftServer server, int radius, String label) {
        String result;
        try {
            Thread.sleep(WARM_UP_MILLIS);
            long cpuBefore = processCpuNanos();
            long tilesBefore = tiles();
            long start = System.nanoTime();
            command(server, "dh pregen start minecraft:overworld 0 0 " + radius);
            boolean done = false;
            while (System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS)) {
                Thread.sleep(POLL_MILLIS);
                String status = command(server, "dh pregen status");
                if (status.contains("not running")) {
                    done = true;
                    break;
                }
            }
            double seconds = (System.nanoTime() - start) / 1e9;
            double cpuSeconds = (processCpuNanos() - cpuBefore) / 1e9;
            int side = 2 * radius;
            result = String.format(Locale.ROOT,
                    "%s: radius %d chunks (%d chunks), %s in %.1f s, %.0f chunks/s, CPU %.0f s (%.1f cores), DH Turbo tiles %d",
                    label, radius, side * side, done ? "done" : "TIMED OUT", seconds, side * side / seconds, cpuSeconds,
                    cpuSeconds / seconds, tiles() - tilesBefore);
            if (Boolean.getBoolean(DhTurbo.MOD_ID + ".pregen.exact")) {
                result += System.lineSeparator() + exactCheck(server);
            }
        } catch (InterruptedException e) {
            return;
        } catch (Exception e) {
            result = label + ": failed: " + e;
            DhTurbo.LOGGER.error("[pregen] benchmark failed", e);
        }
        DhTurbo.LOGGER.info("[pregen] {}", result);
        try {
            Files.write(server.getServerDirectory().toPath().resolve("dhturbo-pregen.txt"),
                    List.of(result + " | " + com.pockyl.dhturbo.surface.BuildProfile.report()),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            DhTurbo.LOGGER.error("[pregen] cannot write the report", e);
        }
        server.execute(() -> server.halt(false));
    }

    /** Runs a command on the server thread as the console and returns what it answered. */
    private static String command(MinecraftServer server, String command) {
        StringBuilder output = new StringBuilder();
        CommandSource capture = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                synchronized (output) {
                    output.append(message.getString()).append('\n');
                }
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CompletableFuture<Void> ran = new CompletableFuture<>();
        server.execute(() -> {
            CommandSourceStack source = server.createCommandSourceStack().withSource(capture);
            server.getCommands().performPrefixedCommand(source, command);
            ran.complete(null);
        });
        ran.join();
        synchronized (output) {
            return output.toString();
        }
    }

    /**
     * Replaces two batches away from spawn with real generation through Distant Horizons' own path, reads back what it
     * stored, and only then generates the real chunks there to compare (a loaded chunk would replace the stored data).
     */
    private static String exactCheck(MinecraftServer server) throws Exception {
        ServerLevel level = server.overworld();
        IDhApiLevelWrapper wrapper = DhIntegration.levelWrapper(level);
        Map<Long, DhApiTerrainDataPoint> fast = storedTops(wrapper);
        long begin = System.nanoTime();
        long handed = DhIntegration.refineNow(level, 2, 2) + DhIntegration.refineNow(level, 3, 2);
        double seconds = (System.nanoTime() - begin) / 1e9;
        Thread.sleep(EXACT_SETTLE_MILLIS);
        Map<Long, DhApiTerrainDataPoint> exact = storedTops(wrapper);
        int[] counts = new int[3];
        for (Map.Entry<Long, DhApiTerrainDataPoint> entry : exact.entrySet()) {
            int x = BlockPos.getX(entry.getKey());
            int z = BlockPos.getZ(entry.getKey());
            BlockPos surface = server.submit(() -> new BlockPos(x, level.getChunk(x >> 4, z >> 4)
                    .getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15), z)).join();
            BlockState real = server.submit(() -> level.getBlockState(surface)).join();
            counts[0]++;
            counts[1] += sameTop(entry.getValue(), surface, real, level) ? 1 : 0;
            DhApiTerrainDataPoint before = fast.get(entry.getKey());
            counts[2] += before != null && sameTop(before, surface, real, level) ? 1 : 0;
        }
        return String.format(Locale.ROOT, "exact pass: %d chunks handed to DH in %.1f s; of %d stored columns the top block "
                + "and height equal the real chunk on %d after the pass, %d before it (trees included)", handed, seconds,
                counts[0], counts[1], counts[2]);
    }

    private static boolean sameTop(DhApiTerrainDataPoint top, BlockPos surface, BlockState real, ServerLevel level) {
        boolean height = top.topYBlockPos == surface.getY() + 1
                || top.topYBlockPos == surface.getY() + 1 - level.getMinBuildHeight();
        return height && top.blockStateWrapper.getWrappedMcObject() == real;
    }

    /** The highest stored block of every third column of the checked area. */
    private static Map<Long, DhApiTerrainDataPoint> storedTops(IDhApiLevelWrapper wrapper) {
        Map<Long, DhApiTerrainDataPoint> tops = new java.util.HashMap<>();
        var cache = DhApi.Delayed.terrainRepo.createSoftCache();
        for (int x = 256; x < 512; x += 3) {
            for (int z = 256; z < 384; z += 3) {
                DhApiResult<DhApiTerrainDataPoint[]> column = DhApi.Delayed.terrainRepo.getColumnDataAtBlockPos(wrapper, x, z, cache);
                if (!column.success || column.payload == null) {
                    continue;
                }
                DhApiTerrainDataPoint top = null;
                for (DhApiTerrainDataPoint point : column.payload) {
                    if (point != null && !point.blockStateWrapper.isAir() && (top == null || point.topYBlockPos > top.topYBlockPos)) {
                        top = point;
                    }
                }
                if (top != null) {
                    tops.put(BlockPos.asLong(x, 0, z), top);
                }
            }
        }
        return tops;
    }

    private static long processCpuNanos() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os
                ? os.getProcessCpuTime() : 0;
    }

    private static long tiles() {
        return TurboActivity.tiles();
    }
}
