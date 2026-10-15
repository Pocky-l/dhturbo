package com.pockyl.dhturbo.compare;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.dh.TurboActivity;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
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
    private static final long POLL_MILLIS = 1_000;
    private static final long TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(60);

    private PregenBenchmark() {
    }

    public static void registerIfRequested() {
        if (System.getProperty(DhTurbo.MOD_ID + ".pregen") != null) {
            NeoForge.EVENT_BUS.register(PregenBenchmark.class);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
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
        } catch (InterruptedException e) {
            return;
        } catch (RuntimeException e) {
            result = label + ": failed: " + e;
            DhTurbo.LOGGER.error("[pregen] benchmark failed", e);
        }
        DhTurbo.LOGGER.info("[pregen] {}", result);
        try {
            Files.write(server.getServerDirectory().resolve("dhturbo-pregen.txt"), List.of(result),
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

    private static long processCpuNanos() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os
                ? os.getProcessCpuTime() : 0;
    }

    private static long tiles() {
        String line = TurboActivity.debugLine().orElse("DH Turbo: 0 tiles");
        String count = line.substring("DH Turbo: ".length(), line.indexOf(' ', "DH Turbo: ".length()));
        return Long.parseLong(count);
    }
}
