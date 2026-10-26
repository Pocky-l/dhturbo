package com.pockyl.dhturbo.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.surface.ColumnBuilder;
import com.pockyl.dhturbo.surface.ProfileTerrain;
import com.pockyl.dhturbo.surface.SurfaceSampler;
import com.pockyl.dhturbo.surface.TerrainCheck;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which levels this mod draws and which it leaves to Distant Horizons because it cannot read their terrain (see
 * {@link TerrainCheck}). No Distant Horizons classes: worldgen sharing uses it on servers without DH.
 */
public final class TerrainSupport {
    /** Levels left to Distant Horizons on this server (the integrated one too), by dimension id, with the reason. */
    private static final Map<ResourceKey<Level>, String> LEFT_TO_DH = new ConcurrentHashMap<>();
    /** Whether clients may generate a level themselves: empty when yes, else the reason. Decided once per level. */
    private static final Map<ServerLevel, Optional<String>> CLIENT_VERDICTS = Collections.synchronizedMap(new WeakHashMap<>());

    private TerrainSupport() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> {
            LEFT_TO_DH.clear();
            CLIENT_VERDICTS.clear();
        });
    }

    /**
     * Why this mod cannot draw the level with the given builder (built with the level's {@code NoiseBinder}), or null
     * when it can. Compares a few dozen columns with the level's own generator; tens of milliseconds.
     */
    @Nullable
    public static String problem(ServerLevel level, ColumnBuilder builder) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        String unreadable = TerrainCheck.unreadable(generator);
        if (unreadable != null) {
            return unreadable;
        }
        TerrainCheck.Result result = TerrainCheck.run(builder, generator, level.getChunkSource().randomState(), level,
                level.getSeed());
        return result.reason();
    }

    /** Remembers that Distant Horizons draws the level itself, for the F3 screen. */
    public static void leftToDistantHorizons(ResourceKey<Level> dimension, String reason) {
        LEFT_TO_DH.put(dimension, reason);
    }

    public static void drawnByUs(ResourceKey<Level> dimension) {
        LEFT_TO_DH.remove(dimension);
    }

    /** Dimension ids this mod leaves to Distant Horizons, sorted. */
    public static Set<String> leftToDistantHorizons() {
        Set<String> ids = new TreeSet<>();
        LEFT_TO_DH.keySet().forEach(key -> ids.add(key.location().toString()));
        return ids;
    }

    /**
     * Why players with this mod may not generate the level's distant terrain themselves, or empty when they may. A
     * client rebuilds the generator from the noise settings alone, without anything a terrain mod attaches to the
     * server's level, so that is what is checked. Decided on first use per level and logged when it fails.
     */
    public static Optional<String> clientProblem(ServerLevel level) {
        Optional<String> known = CLIENT_VERDICTS.get(level);
        if (known != null) {
            return known;
        }
        Optional<String> verdict = Optional.ofNullable(clientCheck(level));
        CLIENT_VERDICTS.put(level, verdict);
        verdict.ifPresent(reason -> DhTurbo.LOGGER.warn(
                "Players with DH Turbo do not generate {} themselves: {}. Distant Horizons provides its distant terrain",
                level.dimension().location(), reason));
        return verdict;
    }

    @Nullable
    private static String clientCheck(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        String unreadable = TerrainCheck.unreadable(generator);
        if (unreadable != null) {
            return unreadable;
        }
        NoiseBasedChunkGenerator noise = (NoiseBasedChunkGenerator) generator;
        try {
            SurfaceSampler sampler = new SurfaceSampler(level.registryAccess(), noise.generatorSettings().value(),
                    noise.getBiomeSource(), level.getSeed());
            ProfileTerrain profile = ColumnBuilder.profiled(level.dimensionType()) ? new ProfileTerrain(sampler) : null;
            return TerrainCheck.run(sampler, profile, generator, level.getChunkSource().randomState(), level,
                    level.getSeed()).reason();
        } catch (RuntimeException e) {
            DhTurbo.LOGGER.warn("Could not check the terrain of {}", level.dimension().location(), e);
            return "its terrain could not be checked";
        }
    }
}
