package com.pockyl.fatamorgana.surface;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;

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
 * Thread-safe: every thread gets its own copy of the cached density function.
 */
public final class SurfaceSampler {
    private final RandomState randomState;
    private final BiomeSource biomeSource;
    private final ThreadLocal<DensityFunction> density;
    private final int minY;
    private final int maxY;
    private final int cellHeight;
    private final int seaLevel;

    public SurfaceSampler(RegistryAccess registries, NoiseGeneratorSettings settings, BiomeSource biomeSource, long seed) {
        NoiseRouter router = settings.noiseRouter();
        DensityFunction surfaceDensity = CaveStripper.strip(registries.registryOrThrow(Registries.DENSITY_FUNCTION),
                router.finalDensity());
        NoiseRouter surfaceRouter = new NoiseRouter(router.barrierNoise(), router.fluidLevelFloodednessNoise(),
                router.fluidLevelSpreadNoise(), router.lavaNoise(), router.temperature(), router.vegetation(),
                router.continents(), router.erosion(), router.depth(), router.ridges(),
                router.initialDensityWithoutJaggedness(), surfaceDensity, router.veinToggle(), router.veinRidged(),
                router.veinGap());
        NoiseGeneratorSettings surfaceSettings = new NoiseGeneratorSettings(settings.noiseSettings(),
                settings.defaultBlock(), settings.defaultFluid(), surfaceRouter, settings.surfaceRule(),
                settings.spawnTarget(), settings.seaLevel(), settings.disableMobGeneration(), settings.aquifersEnabled(),
                settings.oreVeinsEnabled(), settings.useLegacyRandomSource());
        // Same seed and settings apart from the final density: noises are wired identically to the real world.
        this.randomState = RandomState.create(surfaceSettings, registries.lookupOrThrow(Registries.NOISE), seed);
        this.biomeSource = biomeSource;
        DensityFunction wired = randomState.router().finalDensity();
        this.density = ThreadLocal.withInitial(() -> wired.mapAll(new ColumnCachingVisitor()));
        NoiseSettings noise = settings.noiseSettings();
        this.minY = noise.minY();
        this.maxY = noise.minY() + noise.height();
        this.cellHeight = noise.getCellHeight();
        this.seaLevel = settings.seaLevel();
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
