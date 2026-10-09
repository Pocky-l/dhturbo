package com.pockyl.fatamorgana.gametest;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.pockyl.fatamorgana.Fatamorgana;
import com.pockyl.fatamorgana.bench.SurfaceBenchmark;

import java.util.Random;

/**
 * In-game tests, run headless by {@code gradlew runGameTestServer}.
 * Tests use the 1x1x1 {@code empty} structure unless they need a prepared one.
 * Set the environment variable {@code FATAMORGANA_BENCH=1} to also run the full surface benchmark.
 */
@GameTestHolder(Fatamorgana.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ModGameTests {
    private static final long SEED = 20261009L;

    private ModGameTests() {
    }

    @GameTest(template = "empty")
    public static void modLoads(GameTestHelper helper) {
        helper.succeed();
    }

    /** On vanilla cell corners the cave-free surface must match real generation except where caves open up. */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void surfaceMatchesVanilla(GameTestHelper helper) {
        SurfaceBenchmark benchmark = overworldBenchmark(helper.getLevel().registryAccess());
        Random random = new Random(42);
        int[] errors = new int[300];
        int hint = benchmark.fast().seaLevel();
        for (int i = 0; i < errors.length; i++) {
            int x = (random.nextInt(100_000) - 50_000) & ~3;
            int z = (random.nextInt(100_000) - 50_000) & ~3;
            int height = benchmark.fast().surfaceHeight(x, z, hint);
            errors[i] = Math.abs(height - benchmark.vanillaHeight(x, z));
        }
        SurfaceBenchmark.Accuracy accuracy = SurfaceBenchmark.accuracy(errors);
        Fatamorgana.LOGGER.info("Surface vs vanilla on cell corners: {}", accuracy);
        if (accuracy.withinTwo() < 0.85) {
            helper.fail("Surface too far from vanilla: " + accuracy);
        }
        helper.succeed();
    }

    /** Vanilla tree features must run on the virtual level without failing and produce trees in a forest. */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void realTreesGrowInForests(GameTestHelper helper) {
        SurfaceBenchmark benchmark = overworldBenchmark(helper.getLevel().registryAccess());
        Random random = new Random(7);
        int forests = 0;
        int treeColumns = 0;
        long failures = 0;
        for (int attempt = 0; attempt < 400 && forests < 5; attempt++) {
            int x = (random.nextInt(40_000) - 20_000) & ~63;
            int z = (random.nextInt(40_000) - 20_000) & ~63;
            int y = benchmark.fast().surfaceHeight(x + 32, z + 32, benchmark.fast().seaLevel());
            if (y <= benchmark.fast().seaLevel() || !benchmark.fast().biome(x + 32, y, z + 32).is(BiomeTags.IS_FOREST)) {
                continue;
            }
            forests++;
            SurfaceBenchmark.Planted planted = benchmark.plant(x, z, 1);
            treeColumns += planted.treeColumns();
            failures += planted.failures();
        }
        Fatamorgana.LOGGER.info("Real trees in {} forest tiles: {} tree columns, {} failed features", forests, treeColumns,
                failures);
        if (forests == 0) {
            helper.fail("No forest found to test trees in");
        } else if (failures > 0) {
            helper.fail(failures + " tree features failed on the virtual level");
        } else if (treeColumns < forests * 500) {
            helper.fail("Too few trees in forests: " + treeColumns + " columns in " + forests + " tiles");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void benchmark(GameTestHelper helper) {
        if (System.getenv("FATAMORGANA_BENCH") != null) {
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
            for (String line : overworldBenchmark(helper.getLevel().registryAccess()).run(6, threads)) {
                Fatamorgana.LOGGER.info("[bench] {}", line);
            }
        }
        helper.succeed();
    }

    private static SurfaceBenchmark overworldBenchmark(RegistryAccess registries) {
        return new SurfaceBenchmark(registries,
                registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD),
                MultiNoiseBiomeSource.createFromPreset(registries.registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)),
                SEED);
    }
}
