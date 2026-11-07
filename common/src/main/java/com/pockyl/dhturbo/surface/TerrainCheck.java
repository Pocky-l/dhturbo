package com.pockyl.dhturbo.surface;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Random;

/**
 * Checks that this mod reads a level's terrain right before it draws it: the terrain it computes is compared with what
 * the level's own generator answers on a few dozen columns spread over the world. Terrain mods can shape the world in
 * ways the noise settings do not show (their own chunk generator, state attached to the level's random state, mixins
 * into chunk generation); then the heights disagree and the level is better left to Distant Horizons' own generator
 * than drawn wrong.
 */
public final class TerrainCheck {
    /** Columns compared, spread over this many blocks around the world's centre. */
    private static final int COLUMNS = 32;
    private static final int RANGE = 12_000;
    /**
     * Blocks a height may be off and still agree: the real generator's heights include caves that open at the surface,
     * and terrain mods may smooth their terrain in ways a column alone does not show.
     */
    private static final int HEIGHT_TOLERANCE = 6;
    /** Share of columns (heights) or blocks (profiles) that must agree. Vanilla and data-pack worlds agree on 95%+. */
    private static final double REQUIRED = 0.75;
    /** Profiled worlds: every this many blocks of a column are compared. */
    private static final int PROFILE_STEP = 4;
    private static final int PROFILE_COLUMNS = 12;

    private TerrainCheck() {
    }

    /** Outcome of a check; {@code reason} says in a few words why a level cannot be drawn, or is null. */
    public record Result(int agreeing, int compared, @Nullable String reason) {
        public boolean passed() {
            return reason == null;
        }

        static Result of(int agreeing, int compared, String what) {
            boolean passed = compared > 0 && agreeing >= compared * REQUIRED;
            return new Result(agreeing, compared, passed ? null : String.format(Locale.ROOT,
                    "its terrain does not come out of its noise settings as expected (%s agree on %d of %d)",
                    what, agreeing, compared));
        }
    }

    /**
     * Why a level's chunk generator cannot be read at all, or null when it is a noise generator this mod understands:
     * another kind of generator, or a noise generator subclass that fills chunks its own way.
     */
    @Nullable
    public static String unreadable(ChunkGenerator generator) {
        if (!(generator instanceof NoiseBasedChunkGenerator)) {
            return "it uses its own chunk generator (" + generator.getClass().getName() + ")";
        }
        for (Class<?> type = generator.getClass(); type != NoiseBasedChunkGenerator.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals("fillFromNoise")) {
                    return "its chunk generator fills chunks its own way (" + generator.getClass().getName() + ")";
                }
            }
        }
        return null;
    }

    /**
     * Compares the terrain of {@code builder} with the real generator's.
     *
     * @param real      the level's generator
     * @param realState the level's random state, as chunk generation uses it
     */
    public static Result run(ColumnBuilder builder, ChunkGenerator real, RandomState realState, LevelHeightAccessor heights,
                             long seed) {
        return run(builder.sampler(), builder.profile(), real, realState, heights, seed);
    }

    public static Result run(SurfaceSampler sampler, @Nullable ProfileTerrain profile, ChunkGenerator real,
                             RandomState realState, LevelHeightAccessor heights, long seed) {
        Random random = new Random(seed ^ 0x7E44A1CL);
        if (profile != null) {
            return profiles(profile, real, realState, heights, random);
        }
        int agreeing = 0;
        for (int i = 0; i < COLUMNS; i++) {
            int x = random.nextInt(2 * RANGE) - RANGE;
            int z = random.nextInt(2 * RANGE) - RANGE;
            int ours = sampler.surfaceHeight(x, z, sampler.seaLevel());
            int theirs = real.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, heights, realState);
            if (Math.abs(ours - theirs) <= HEIGHT_TOLERANCE) {
                agreeing++;
            }
        }
        return Result.of(agreeing, COLUMNS, "heights");
    }

    /** Worlds without a single surface: solid or open, block by block, over whole columns. */
    private static Result profiles(ProfileTerrain profile, ChunkGenerator real, RandomState realState,
                                   LevelHeightAccessor heights, Random random) {
        ProfileTerrain.Columns ours = profile.columns();
        int agreeing = 0;
        int compared = 0;
        for (int i = 0; i < PROFILE_COLUMNS; i++) {
            int x = random.nextInt(2 * RANGE) - RANGE;
            int z = random.nextInt(2 * RANGE) - RANGE;
            NoiseColumn column = real.getBaseColumn(x, z, heights, realState);
            for (int y = profile.minY(); y < profile.minY() + profile.height(); y += PROFILE_STEP) {
                BlockState state = column.getBlock(y);
                boolean solid = !state.isAir() && state.getFluidState().isEmpty();
                if (solid == ours.solid(x, y, z)) {
                    agreeing++;
                }
                compared++;
            }
        }
        return Result.of(agreeing, compared, "blocks");
    }
}
