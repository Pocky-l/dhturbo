package com.pockyl.dhturbo.client;

import com.seibel.distanthorizons.core.level.DhClientLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.dh.LodTileWriter;
import com.pockyl.dhturbo.dh.WorkerPool;
import com.pockyl.dhturbo.world.LevelGenInfo;
import com.pockyl.dhturbo.world.ReceivedWorldgen;
import com.pockyl.dhturbo.world.WorldgenLoader;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side generation on servers that share their world generation ({@link ReceivedWorldgen}): builds one tile
 * writer per level from the client's own data packs, in the background, and only when it matches the server's.
 */
public final class ClientGeneration {
    private static final Map<LevelGenInfo, CompletableFuture<Optional<LodTileWriter>>> WRITERS = new ConcurrentHashMap<>();

    private ClientGeneration() {
    }

    /** Forgets everything about the server we left. */
    public static void reset() {
        ReceivedWorldgen.set(null);
        WRITERS.clear();
    }

    /** Whether DH Turbo generates this multiplayer level on the client right now. */
    public static boolean active(DhClientLevel level) {
        return writer(level) != null;
    }

    /** The tile writer for the level, or null while it is being built or when the level cannot be generated here. */
    @Nullable
    public static LodTileWriter writer(DhClientLevel level) {
        if (!Config.ENABLED.get() || !ReceivedWorldgen.present() || ((ClientLevelState) level).dhturbo$serverSendsLods()
                || !(level.levelWrapper.getWrappedMcObject() instanceof ClientLevel clientLevel)
                || clientLevel.dimensionType().hasCeiling()) {
            return null;
        }
        LevelGenInfo info = ReceivedWorldgen.forDimension(clientLevel.dimension());
        if (info == null) {
            return null;
        }
        long seed = ReceivedWorldgen.seed();
        CompletableFuture<Optional<LodTileWriter>> writer = WRITERS.computeIfAbsent(info,
                key -> CompletableFuture.supplyAsync(() -> build(level, clientLevel, key, seed), WorkerPool.get()));
        return writer.isDone() ? writer.join().orElse(null) : null;
    }

    private static Optional<LodTileWriter> build(DhClientLevel level, ClientLevel clientLevel, LevelGenInfo info, long seed) {
        try {
            RegistryAccess worldgen = WorldgenLoader.get();
            LevelGenInfo.Resolved resolved = info.resolve(worldgen);
            if (resolved == null) {
                DhTurbo.LOGGER.warn("The server generates {} with terrain this game does not have (missing mods or data packs); "
                        + "distant terrain there comes from Distant Horizons only", info);
                return Optional.empty();
            }
            Registry<Biome> levelBiomes = clientLevel.registryAccess().registryOrThrow(Registries.BIOME);
            LodTileWriter writer = new LodTileWriter(level.levelWrapper, resolved.generator(), worldgen,
                    clientLevel.dimensionType(), seed,
                    biome -> biome.unwrapKey().flatMap(levelBiomes::getHolder).<Holder<Biome>>map(holder -> holder).orElse(biome),
                    null);
            DhTurbo.LOGGER.info("Generating distant terrain of {} on this client", info);
            return Optional.of(writer);
        } catch (RuntimeException e) {
            DhTurbo.LOGGER.error("Could not set up client-side generation for {}", info, e);
            return Optional.empty();
        }
    }
}
