package com.pockyl.dhturbo.surface;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Interval;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.ContextBoundSampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySamplerSet;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;

import com.pockyl.dhturbo.DhTurbo;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Finds the terrain surface of a noise-based world without generating chunks.
 * <p>
 * Three things make it cheap compared to marching the final density from the top of the world:
 * <ul>
 *     <li>the density is sampled only on vanilla's cell corners (every {@code cellHeight} blocks) and the surface is
 *     found by linear interpolation between them, which is exactly how vanilla interpolates terrain;</li>
 *     <li>2D parts of the density are cached per column: the game compiles a function that does not depend on Y down
 *     to a sample at Y 0, and every thread's sampler context remembers the last value of each {@code cache} node;</li>
 *     <li>caves are removed ({@link CaveStripper}) and the search starts at the height of the previous column, so a
 *     typical column needs two or three probes.</li>
 * </ul>
 * <p>
 * The density probed is the one inside vanilla's {@code interpolated} node, not the final density: vanilla
 * interpolates that value between cell corners and only then squeezes (and clamps) it, which keeps the sign but bends
 * the values. Interpolating the squeezed final density put the surface up to several blocks off between corners.
 * When a world's final density is not a sign-keeping function of a single interpolated value (checked on sample
 * points), the final density is used.
 * <p>
 * Thread-safe: every thread samples through its own context.
 */
public final class SurfaceSampler {
    private static final int SIGNAL_CHECKS = 256;
    /** A world whose density has no interpolated node: vanilla's overworld cells. */
    private static final int DEFAULT_CELL_WIDTH = 4;
    private static final int DEFAULT_CELL_HEIGHT = 8;
    /** Takes out cell interpolation: the value at a point is then the function's own, as it is on a cell corner. */
    private static final DfRewriteRule PLAIN = new DfRewriteRule() {
        @Override
        public DensityFunction rewrite(DensityFunction function) {
            return function instanceof InterpolatedFunction interpolated ? rewrite(interpolated.input()) : function.rewriteChildren(this);
        }
    };

    private final NoiseBinder binder;
    private final RandomState randomState;
    private final BiomeSource biomeSource;
    private final ThreadLocal<ColumnNoises> noises;
    /** The density the surface search probes, with its cell interpolation taken out. */
    private final DensityFunction density;
    /**
     * The same value with caves, as vanilla interpolates it: below the surface it carries the cave noises, which pull
     * the surface between cell corners. Only used inside the cell the cave-free search found.
     */
    private final DensityFunction realDensity;
    /**
     * The final density (without caves) computed from a given interpolated value: the world's final density with its
     * {@code interpolated} node replaced by {@link Probe}. Null when the probed value is the final density itself.
     */
    @Nullable
    private final DensityFunction finalFromProbe;
    private final int minY;
    private final int maxY;
    private final int cellHeight;
    private final int cellWidth;
    private final int seaLevel;

    public SurfaceSampler(RegistryAccess registries, NoiseGeneratorSettings settings, BiomeSource biomeSource, long seed) {
        this(registries, settings, biomeSource, seed, NoiseBinder.NONE);
    }

    /** @param binder prepares the random states built here like the level's own one (terrain mods that need it) */
    public SurfaceSampler(RegistryAccess registries, NoiseGeneratorSettings settings, BiomeSource biomeSource, long seed,
                          NoiseBinder binder) {
        this.binder = binder;
        NoiseRouter router = settings.noiseRouter();
        DensityFunction surfaceDensity = CaveStripper.strip(router.finalDensity());
        // Same seed and settings: the noises are the real world's, whatever function is compiled from them.
        this.randomState = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings.useLegacyRandomSource(),
                settings.defaultBlock(), settings.seaLevel(), withCachedSurfaceCorners(router));
        binder.bind(randomState);
        this.biomeSource = biomeSource;
        DensityFunction wired = signal(surfaceDensity, settings.noiseSettings());
        this.density = plain(wired);
        // With caves the final density holds more interpolated values (noodle caves have their own, and vanilla adds
        // them per block, outside the terrain's). The terrain's is the one that loses its caves the same way.
        DensityFunction realInner = null;
        List<InterpolatedFunction> stripped = interpolated(surfaceDensity);
        if (wired != surfaceDensity && stripped.size() == 1) {
            for (InterpolatedFunction candidate : interpolated(router.finalDensity())) {
                if (CaveStripper.strip(candidate.input()).equals(stripped.getFirst().input())) {
                    realInner = candidate.input();
                    break;
                }
            }
        }
        this.realDensity = plain(realInner != null ? realInner : router.finalDensity());
        this.finalFromProbe = wired != surfaceDensity && realInner != null ? Probe.into(surfaceDensity) : null;
        this.noises = ThreadLocal.withInitial(ColumnNoises::new);
        NoiseSettings noise = settings.noiseSettings();
        this.minY = noise.minY();
        this.maxY = noise.minY() + noise.height();
        // The cell size moved from the noise settings into the interpolated nodes of the density.
        List<InterpolatedFunction> cells = stripped.isEmpty() ? interpolated(router.finalDensity()) : stripped;
        this.cellHeight = cells.isEmpty() ? DEFAULT_CELL_HEIGHT : cells.getFirst().cellSizeY();
        this.cellWidth = cells.isEmpty() ? DEFAULT_CELL_WIDTH : cells.getFirst().cellSizeXz();
        this.seaLevel = settings.seaLevel();
    }

    /**
     * The surface rules ask for the preliminary surface of every column, interpolated between corners that each cost a
     * search down the column: columns of one cell share them this way. The router is only read for this function here.
     */
    private static NoiseRouter withCachedSurfaceCorners(NoiseRouter router) {
        DensityFunction surfaceLevel = new DfRewriteRule() {
            @Override
            public DensityFunction rewrite(DensityFunction function) {
                return function instanceof InterpolatedFunction(DensityFunction input, int cellSizeXz, int cellSizeY)
                        ? new InterpolatedFunction(DensityFunctions.cache(input), cellSizeXz, cellSizeY)
                        : function.rewriteChildren(this);
            }
        }.rewrite(router.chunkSurfaceLevel());
        return new NoiseRouter(router.temperature(), router.vegetation(), router.continents(), router.erosion(), router.depth(),
                router.ridges(), surfaceLevel, router.finalDensity());
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public int seaLevel() {
        return seaLevel;
    }

    /**
     * Returns the Y of the first non-solid block above the terrain at the column, like a {@code OCEAN_FLOOR_WG}
     * heightmap (water is not solid). {@code hintY} is where the search starts: the height of a nearby column.
     */
    public int surfaceHeight(int x, int z, int hintY) {
        DensitySampler.Bound function = noises.get().density;
        int y = Mth.clamp(Math.floorDiv(hintY - minY, cellHeight) * cellHeight + minY, minY, maxY - cellHeight);
        double value = function.sampleValue(x, y, z);
        if (value > 0) {
            while (true) {
                int above = y + cellHeight;
                if (above >= maxY) {
                    return maxY;
                }
                double aboveValue = function.sampleValue(x, above, z);
                if (aboveValue <= 0) {
                    return crossing(y, value, aboveValue);
                }
                y = above;
                value = aboveValue;
            }
        }
        while (true) {
            int below = y - cellHeight;
            if (below < minY) {
                return minY;
            }
            double belowValue = function.sampleValue(x, below, z);
            if (belowValue > 0) {
                return crossing(below, belowValue, value);
            }
            y = below;
            value = belowValue;
        }
    }

    /** The function without cell interpolation. */
    public static DensityFunction plain(DensityFunction function) {
        return PLAIN.rewrite(function);
    }

    /** The interpolated nodes of a function. */
    public static List<InterpolatedFunction> interpolated(DensityFunction function) {
        List<InterpolatedFunction> interpolated = new ArrayList<>();
        new DfRewriteRule() {
            @Override
            public DensityFunction rewrite(DensityFunction node) {
                if (node instanceof InterpolatedFunction found) {
                    interpolated.add(found);
                }
                return node.rewriteChildren(this);
            }
        }.rewrite(function);
        return interpolated;
    }

    /**
     * The value vanilla interpolates between cell corners, if the final density is a sign-keeping function of it
     * (vanilla: {@code min(squeeze(interpolated(0.64 * ...)), caves)} with caves stripped); otherwise the final density.
     */
    private DensityFunction signal(DensityFunction finalDensity, NoiseSettings noise) {
        List<InterpolatedFunction> interpolated = interpolated(finalDensity);
        if (interpolated.size() != 1) {
            return finalDensity;
        }
        DensityFunction inner = interpolated.getFirst().input();
        SamplerContext context = SamplerContext.builder().enableCaches().build();
        DensitySampler.Bound plainFinal = randomState.getSampler(plain(finalDensity)).bind(context);
        DensitySampler.Bound plainInner = randomState.getSampler(plain(inner)).bind(context);
        Random random = new Random(1);
        for (int i = 0; i < SIGNAL_CHECKS; i++) {
            int x = random.nextInt(60_000) - 30_000;
            int z = random.nextInt(60_000) - 30_000;
            int y = noise.minY() + random.nextInt(noise.height());
            double outer = plainFinal.sampleValue(x, y, z);
            if (Math.abs(outer) > 1e-9 && Math.signum(outer) != Math.signum(plainInner.sampleValue(x, y, z))) {
                return finalDensity;
            }
        }
        return inner;
    }

    /** The probed density at a block: positive is solid. Only meaningful on cell corners. */
    public double density(int x, int y, int z) {
        return noises.get().density.sampleValue(x, y, z);
    }

    /** The interpolated density with caves at a cell corner, as vanilla interpolates it. */
    public double realDensity(int x, int y, int z) {
        return noises.get().realDensity.sampleValue(x, y, z);
    }

    /**
     * The final density at a block as chunk generation computes it, without caves: the interpolated value is
     * interpolated trilinearly between the cell corners ({@code corners} gives their {@link #realDensity}, usually
     * cached by the caller) and then goes through the rest of the final density. Positive is solid.
     */
    public double blockDensity(int x, int y, int z, CornerSource corners) {
        if (finalFromProbe == null) {
            // The final density is probed directly; caves are not part of it.
            return density(x, y, z);
        }
        int x0 = Math.floorDiv(x, cellWidth) * cellWidth;
        int y0 = Math.floorDiv(y - minY, cellHeight) * cellHeight + minY;
        int z0 = Math.floorDiv(z, cellWidth) * cellWidth;
        double fx = (double) (x - x0) / cellWidth;
        double fy = (double) (y - y0) / cellHeight;
        double fz = (double) (z - z0) / cellWidth;
        int x1 = x0 + cellWidth;
        int y1 = y0 + cellHeight;
        int z1 = z0 + cellWidth;
        double value = Mth.lerp3(fx, fy, fz,
                corners.at(x0, y0, z0), corners.at(x1, y0, z0), corners.at(x0, y1, z0), corners.at(x1, y1, z0),
                corners.at(x0, y0, z1), corners.at(x1, y0, z1), corners.at(x0, y1, z1), corners.at(x1, y1, z1));
        return finalDensity(value, x, y, z);
    }

    /**
     * The final density at a block from the value interpolated there between cell corners' {@link #realDensity}
     * (when the world's final density cannot be split that way, {@link #realDensity} already is the final density and
     * the value is returned as it is). Positive is solid.
     */
    public double finalDensity(double interpolated, int x, int y, int z) {
        if (finalFromProbe == null) {
            return interpolated;
        }
        ColumnNoises column = noises.get();
        column.probe.value = (float) interpolated;
        return column.finalFromProbe.sampleValue(x, y, z);
    }

    /** Interpolated values at cell corners, see {@link #blockDensity}. */
    @FunctionalInterface
    public interface CornerSource {
        double at(int x, int y, int z);
    }

    /** One thread's terrain density and climate, sharing the caches of what they have in common. */
    private final class ColumnNoises {
        final ProbeValue probe = new ProbeValue();
        final SamplerContext context = SamplerContext.builder()
                .setUserFields(ContextMap.builder().set(Probe.VALUE, probe).build()).enableCaches().build();
        final DensitySampler.Bound density = randomState.getSampler(SurfaceSampler.this.density).bind(context);
        final DensitySampler.Bound realDensity = randomState.getSampler(SurfaceSampler.this.realDensity).bind(context);
        @Nullable
        final DensitySampler.Bound finalFromProbe = SurfaceSampler.this.finalFromProbe == null ? null
                : randomState.getSampler(SurfaceSampler.this.finalFromProbe).bind(context);
        final BiomeResolver biomes = biomeSource.createResolver(randomState.createClimateSampler(context));
    }

    /** What one thread's {@link Probe} answers. */
    private static final class ProbeValue implements DensitySampler {
        private float value;

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer outputBuffer, DensityVolume volume) {
            outputBuffer.fill(value);
        }

        @Override
        public float sampleValue(SamplerContext context, int blockX, int blockY, int blockZ) {
            return value;
        }
    }

    /** Stands in for the interpolated value inside a final density; each thread's context holds the value to use. */
    private enum Probe implements DensityFunction {
        INSTANCE;

        static final ContextKey<ProbeValue> VALUE = new ContextKey<>(DhTurbo.id("probe"));

        static DensityFunction into(DensityFunction finalDensity) {
            return new DfRewriteRule() {
                @Override
                public DensityFunction rewrite(DensityFunction function) {
                    return function instanceof InterpolatedFunction ? INSTANCE : function.rewriteChildren(this);
                }
            }.rewrite(finalDensity);
        }

        @Override
        public DensitySampler compileSampler(CompileContext context) {
            return new ContextBoundSampler(VALUE, new ProbeValue());
        }

        @Override
        public DensityFunction rewriteChildren(DfRewriteRule rule) {
            return this;
        }

        @Override
        public Interval range() {
            return Interval.of(-1e6F, 1e6F);
        }

        @Override
        public int domainAxes() {
            return ALL_AXES;
        }

        @Override
        public MapCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("Probes are not serializable");
        }
    }

    /** Vanilla cell width: the density is interpolated between corners this many blocks apart horizontally. */
    public int cellWidth() {
        return cellWidth;
    }

    public int cellHeight() {
        return cellHeight;
    }

    /** Prepares further random states of this world (structures) like the sampler's own. */
    public NoiseBinder binder() {
        return binder;
    }

    /** The world's noises (same seed and wiring as the real world). */
    public RandomState randomState() {
        return randomState;
    }

    /** Density samplers of the calling thread; use them on that thread only. */
    public DensitySamplerSet samplers() {
        return randomState.samplersWithContext(noises.get().context);
    }

    /** Biome of a quart (4x4x4 blocks), as chunk generation stores it. */
    public Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ) {
        return noises.get().biomes.getNoiseBiome(quartX, quartY, quartZ);
    }

    /** Biome at the given block, sampled the way chunk generation does. */
    public Holder<Biome> biome(int x, int y, int z) {
        return noises.get().biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
    }

    /** First non-solid Y between a solid cell corner {@code lowY} and the non-solid corner above it. */
    private int crossing(int lowY, double lowValue, double highValue) {
        double blocks = lowValue / (lowValue - highValue) * cellHeight;
        return lowY + Math.min(cellHeight, (int) Math.ceil(blocks));
    }
}
