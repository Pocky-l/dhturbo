package com.pockyl.dhturbo.surface;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

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
 *     <li>2D parts of the density are cached per column ({@link ColumnCachingVisitor});</li>
 *     <li>caves are removed ({@link CaveStripper}) and the search starts at the height of the previous column, so a
 *     typical column needs two or three probes.</li>
 * </ul>
 * <p>
 * The density probed is the one inside vanilla's {@code interpolated} marker, not the final density: vanilla
 * interpolates that value between cell corners and only then squeezes (and clamps) it, which keeps the sign but bends
 * the values. Interpolating the squeezed final density put the surface up to several blocks off between corners.
 * When a world's final density is not a sign-keeping function of a single interpolated value (checked on sample
 * points), the final density is used.
 * <p>
 * Thread-safe: every thread gets its own copy of the cached density function.
 */
public final class SurfaceSampler {
    private final RandomState randomState;
    private final BiomeSource biomeSource;
    private final ThreadLocal<DensityFunction> density;
    /**
     * The same value with caves, as vanilla interpolates it: below the surface it carries the cave noises, which pull
     * the surface between cell corners. Only used inside the cell the cave-free search found.
     */
    private final ThreadLocal<DensityFunction> realDensity;
    private final int minY;
    private final int maxY;
    private final int cellHeight;
    private final int seaLevel;

    public SurfaceSampler(RegistryAccess registries, NoiseGeneratorSettings settings, BiomeSource biomeSource, long seed) {
        NoiseRouter router = settings.noiseRouter();
        Registry<DensityFunction> functions = registries.registryOrThrow(Registries.DENSITY_FUNCTION);
        DensityFunction surfaceDensity = CaveStripper.strip(functions, router.finalDensity());
        // Same seed and settings apart from the final density: noises are wired identically to the real world.
        this.randomState = RandomState.create(withFinalDensity(settings, surfaceDensity),
                registries.lookupOrThrow(Registries.NOISE), seed);
        this.biomeSource = biomeSource;
        DensityFunction strippedFinal = randomState.router().finalDensity();
        DensityFunction wired = signal(strippedFinal, settings.noiseSettings());
        this.density = ThreadLocal.withInitial(() -> wired.mapAll(new ColumnCachingVisitor()));
        // With caves the final density holds more interpolated values (noodle caves have their own, and vanilla adds
        // them per block, outside the terrain's). The terrain's is the one that loses its caves the same way.
        DensityFunction realInner = null;
        List<DensityFunction> stripped = interpolated(surfaceDensity);
        if (wired != strippedFinal && stripped.size() == 1) {
            for (DensityFunction candidate : interpolated(router.finalDensity())) {
                if (CaveStripper.strip(functions, candidate).equals(stripped.getFirst())) {
                    realInner = candidate;
                    break;
                }
            }
        }
        DensityFunction realWired = RandomState.create(withFinalDensity(settings, realInner != null ? realInner : router.finalDensity()),
                registries.lookupOrThrow(Registries.NOISE), seed).router().finalDensity();
        this.realDensity = ThreadLocal.withInitial(() -> realWired.mapAll(new ColumnCachingVisitor()));
        NoiseSettings noise = settings.noiseSettings();
        this.minY = noise.minY();
        this.maxY = noise.minY() + noise.height();
        this.cellHeight = noise.getCellHeight();
        this.seaLevel = settings.seaLevel();
    }

    @SuppressWarnings("deprecation") // disableMobGeneration has to be copied over to rebuild the settings record
    private static NoiseGeneratorSettings withFinalDensity(NoiseGeneratorSettings settings, DensityFunction finalDensity) {
        NoiseRouter router = settings.noiseRouter();
        NoiseRouter changed = new NoiseRouter(router.barrierNoise(), router.fluidLevelFloodednessNoise(),
                router.fluidLevelSpreadNoise(), router.lavaNoise(), router.temperature(), router.vegetation(),
                router.continents(), router.erosion(), router.depth(), router.ridges(),
                router.initialDensityWithoutJaggedness(), finalDensity, router.veinToggle(), router.veinRidged(),
                router.veinGap());
        return new NoiseGeneratorSettings(settings.noiseSettings(), settings.defaultBlock(), settings.defaultFluid(),
                changed, settings.surfaceRule(), settings.spawnTarget(), settings.seaLevel(),
                settings.disableMobGeneration(), settings.aquifersEnabled(), settings.oreVeinsEnabled(),
                settings.useLegacyRandomSource());
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
        DensityFunction function = density.get();
        int y = Mth.clamp(Math.floorDiv(hintY - minY, cellHeight) * cellHeight + minY, minY, maxY - cellHeight);
        double value = function.compute(new DensityFunction.SinglePointContext(x, y, z));
        if (value > 0) {
            while (true) {
                int above = y + cellHeight;
                if (above >= maxY) {
                    return maxY;
                }
                double aboveValue = function.compute(new DensityFunction.SinglePointContext(x, above, z));
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
            double belowValue = function.compute(new DensityFunction.SinglePointContext(x, below, z));
            if (belowValue > 0) {
                return crossing(below, belowValue, value);
            }
            y = below;
            value = belowValue;
        }
    }

    private static final KeyDispatchDataCodec<? extends DensityFunction> INTERPOLATED =
            DensityFunctions.interpolated(DensityFunctions.zero()).codec();
    private static final int SIGNAL_CHECKS = 256;

    /**
     * The value vanilla interpolates between cell corners, if the final density is a sign-keeping function of it
     * (vanilla: {@code min(squeeze(0.64 * interpolated(...)), caves)} with caves stripped); otherwise the final density.
     */
    public static List<DensityFunction> interpolated(DensityFunction function) {
        List<DensityFunction> interpolated = new ArrayList<>();
        function.mapAll(node -> {
            if (node instanceof DensityFunctions.MarkerOrMarked marker && marker.codec() == INTERPOLATED) {
                interpolated.add(marker.wrapped());
            }
            return node;
        });
        return interpolated;
    }

    private static DensityFunction signal(DensityFunction finalDensity, NoiseSettings noise) {
        List<DensityFunction> interpolated = interpolated(finalDensity);
        if (interpolated.size() != 1) {
            return finalDensity;
        }
        DensityFunction inner = interpolated.getFirst();
        DensityFunction plainFinal = finalDensity.mapAll(new ColumnCachingVisitor());
        DensityFunction plainInner = inner.mapAll(new ColumnCachingVisitor());
        Random random = new Random(1);
        for (int i = 0; i < SIGNAL_CHECKS; i++) {
            int x = random.nextInt(60_000) - 30_000;
            int z = random.nextInt(60_000) - 30_000;
            int y = noise.minY() + random.nextInt(noise.height());
            DensityFunction.SinglePointContext point = new DensityFunction.SinglePointContext(x, y, z);
            double outer = plainFinal.compute(point);
            if (Math.abs(outer) > 1e-9 && Math.signum(outer) != Math.signum(plainInner.compute(point))) {
                return finalDensity;
            }
        }
        return inner;
    }

    /** A density function rewritten with this mod's column caches (for comparisons). */
    public static DensityFunction columnCached(DensityFunction function) {
        return function.mapAll(new ColumnCachingVisitor());
    }

    /** The probed density at a block: positive is solid. Only meaningful on cell corners. */
    public double density(int x, int y, int z) {
        return density.get().compute(new DensityFunction.SinglePointContext(x, y, z));
    }

    /** The interpolated density with caves at a cell corner, as vanilla interpolates it. */
    public double realDensity(int x, int y, int z) {
        return realDensity.get().compute(new DensityFunction.SinglePointContext(x, y, z));
    }

    public int cellHeight() {
        return cellHeight;
    }

    /** The world's noises (same seed and wiring as the real world; the final density has no caves). */
    public RandomState randomState() {
        return randomState;
    }

    /** Biome of a quart (4x4x4 blocks), as chunk generation stores it. */
    public Holder<Biome> noiseBiome(int quartX, int quartY, int quartZ) {
        return biomeSource.getNoiseBiome(quartX, quartY, quartZ, randomState.sampler());
    }

    /** Biome at the given block, sampled the way chunk generation does. */
    public Holder<Biome> biome(int x, int y, int z) {
        return biomeSource.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z),
                randomState.sampler());
    }

    /** First non-solid Y between a solid cell corner {@code lowY} and the non-solid corner above it. */
    private int crossing(int lowY, double lowValue, double highValue) {
        double blocks = lowValue / (lowValue - highValue) * cellHeight;
        return lowY + Math.min(cellHeight, (int) Math.ceil(blocks));
    }
}
