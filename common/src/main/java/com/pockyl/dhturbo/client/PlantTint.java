package com.pockyl.dhturbo.client;

import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Soft plant colours for Distant Horizons LODs, driven by {@code FullDataToRenderDataTransformerMixin}.
 * <p>
 * DH skips plants (non-colliding blocks) when it builds LOD geometry and, with its {@code tintWithAvoidedBlocks}
 * option on (the default), paints the block under a plant with the plant's colour: from afar every poppy is a red
 * block and every dandelion a yellow one. Here the ground keeps its own colour and material, and the plant's colour is
 * blended in: a little for flowers, more for grass, which also shades the ground a bit, so meadows read as grass.
 * <p>
 * DH builds a column top-down: the plant's colour arrives first ({@link #ignoredColor}, {@link #tintBelow}), the
 * ground's next ({@link #groundColor}), then the ground's material ({@link #material}). State is per thread, reset per
 * column.
 */
public final class PlantTint {
    /** How much of a plant's colour goes into the ground under it, and how much the ground darkens. */
    private record Tint(float weight, float shade) {
    }

    private static final Tint FLOWER = new Tint(0.3f, 1.0f);
    private static final Tint GRASS = new Tint(0.5f, 0.88f);
    private static final Tint OTHER = new Tint(0.25f, 1.0f);
    /**
     * Snow layers and powder snow have no collision either, but they are the ground's colour from above: DH's full tint
     * stays. With the grass blend, snowy plains showed grass-green columns.
     */
    private static final Tint FULL = new Tint(1.0f, 1.0f);

    private static final class State {
        int ignoredColor;
        IBlockStateWrapper ignored;
        boolean pending;
        int plantColor;
        Tint tint;
        int groundMaterial = -1;
    }

    private static final ThreadLocal<State> STATE = ThreadLocal.withInitial(State::new);
    private static final Map<IBlockStateWrapper, Tint> TINTS = new ConcurrentHashMap<>();
    private static volatile boolean broken;

    private PlantTint() {
    }

    public static void startColumn() {
        State state = STATE.get();
        state.pending = false;
        state.ignored = null;
        state.groundMaterial = -1;
    }

    /** DH computed the colour of a skipped (non-colliding) block. */
    public static void ignoredColor(IBlockStateWrapper block, int color) {
        State state = STATE.get();
        state.ignored = block;
        state.ignoredColor = color;
    }

    /**
     * DH is about to paint the next block with the skipped block's colour ({@code painted}). Returns the colour to paint
     * with, or -1 for "none": the next block then takes its own colour and {@link #groundColor} blends the plant in.
     */
    public static int tintBelow(int painted) {
        if (broken || !Config.SOFT_PLANT_COLORS.get()) {
            return painted;
        }
        State state = STATE.get();
        if (state.ignored == null) {
            return painted;
        }
        state.pending = true;
        state.plantColor = state.ignoredColor;
        state.tint = tint(state.ignored);
        return -1;
    }

    /** DH computed the own colour of a block that gets drawn. */
    public static int groundColor(IBlockStateWrapper block, int color) {
        State state = STATE.get();
        state.groundMaterial = -1;
        if (!state.pending) {
            return color;
        }
        state.pending = false;
        state.groundMaterial = block.getMaterialId();
        return blend(color, state.plantColor, state.tint);
    }

    /**
     * Material of the block being drawn. DH swaps in the plant's block there (material "unknown"), which loses the
     * ground's material shaders rely on (grass, sand...); the ground's is kept instead.
     */
    public static byte material(byte original) {
        State state = STATE.get();
        if (state.groundMaterial < 0) {
            return original;
        }
        byte material = (byte) state.groundMaterial;
        state.groundMaterial = -1;
        return material;
    }

    /** Called when a hook failed: DH's own behaviour from now on. */
    public static void fail(Throwable e) {
        if (!broken) {
            broken = true;
            DhTurbo.LOGGER.error("DH Turbo's plant colours failed and are switched off", e);
        }
        startColumn();
    }

    public static boolean broken() {
        return broken;
    }

    private static Tint tint(IBlockStateWrapper block) {
        return TINTS.computeIfAbsent(block, key -> {
            if (!(key.getWrappedMcObject() instanceof BlockState state)) {
                return OTHER;
            }
            if (state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SNOW)) {
                return FULL;
            }
            if (state.is(BlockTags.FLOWERS)) {
                return FLOWER;
            }
            if (state.getBlock() instanceof BushBlock || state.is(BlockTags.REPLACEABLE_BY_TREES)) {
                return GRASS;
            }
            return OTHER;
        });
    }

    /** ARGB blend, alpha of the ground. */
    static int blend(int ground, int plant, Tint tint) {
        float w = tint.weight;
        int r = channel(ground >> 16, plant >> 16, w, tint.shade);
        int g = channel(ground >> 8, plant >> 8, w, tint.shade);
        int b = channel(ground, plant, w, tint.shade);
        return (ground & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int channel(int ground, int plant, float weight, float shade) {
        float mixed = ((ground & 0xFF) * (1 - weight) + (plant & 0xFF) * weight) * shade;
        return Math.min(255, Math.max(0, Math.round(mixed)));
    }
}
