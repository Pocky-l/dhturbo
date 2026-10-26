package com.pockyl.dhturbo.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.RandomState;

import com.pockyl.dhturbo.DhTurbo;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ReTerraForged builds its terrain in its own heightmap (continents, rivers, erosion) and reads it into the noise
 * router through {@code CellSampler} density functions. Every {@link RandomState} gets the samplers, but only the
 * level's own one is given the heightmap ({@code RTFRandomState.initialize(ServerLevel)}, called by its
 * {@code ChunkMap} mixin); in any other the samplers return 0 and no column comes out at its real height. This initializes the
 * random states the generator builds the same way. Reflection only: the mod is optional and its API is internal.
 */
public final class ReTerraForged {
    private static final String RANDOM_STATE = "raccoonman.reterraforged.world.worldgen.RTFRandomState";
    private static final AtomicBoolean WARNED = new AtomicBoolean();
    @Nullable
    private static final Hooks HOOKS = Hooks.find();

    private ReTerraForged() {
    }

    private record Hooks(Class<?> randomState, Method initialize, Method generatorContext) {
        @Nullable
        static Hooks find() {
            try {
                Class<?> type = Class.forName(RANDOM_STATE, false, ReTerraForged.class.getClassLoader());
                return new Hooks(type, type.getMethod("initialize", ServerLevel.class), type.getMethod("generatorContext"));
            } catch (ClassNotFoundException e) {
                return null;
            } catch (ReflectiveOperationException | LinkageError e) {
                DhTurbo.LOGGER.warn("ReTerraForged is installed but its terrain hooks changed: its levels are left to Distant Horizons", e);
                return null;
            }
        }
    }

    /** Whether the level's terrain comes from ReTerraForged's heightmap. */
    public static boolean shapes(ServerLevel level) {
        return HOOKS != null && context(level.getChunkSource().randomState()) != null;
    }

    /**
     * Gives a random state built for the level the level's ReTerraForged heightmap, as the level's own random state has
     * it. Does nothing for levels without one. A failure is logged once and leaves the samplers at 0, which the
     * terrain check ({@code TerrainCheck}) then reports, so the level is left to Distant Horizons.
     */
    public static void bind(RandomState state, ServerLevel level) {
        if (HOOKS == null || !HOOKS.randomState().isInstance(state) || !shapes(level)) {
            return;
        }
        try {
            HOOKS.initialize().invoke(state, level);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            if (WARNED.compareAndSet(false, true)) {
                DhTurbo.LOGGER.warn("Could not read ReTerraForged's terrain for {}", level.dimension().location(), e);
            }
        }
    }

    @Nullable
    private static Object context(RandomState state) {
        if (HOOKS == null || !HOOKS.randomState().isInstance(state)) {
            return null;
        }
        try {
            return HOOKS.generatorContext().invoke(state);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            return null;
        }
    }
}
