package com.pockyl.dhturbo.gametest;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.bench.SurfaceBenchmark;
import com.pockyl.dhturbo.surface.SurfaceSampler;
import com.pockyl.dhturbo.world.LevelGenInfo;
import com.pockyl.dhturbo.world.WorldgenLoader;

import java.util.Random;

/**
 * In-game tests, run headless by {@code gradlew runGameTestServer}.
 * Tests use the 1x1x1 {@code empty} structure unless they need a prepared one.
 * Set the environment variable {@code DHTURBO_BENCH=1} to also run the full surface benchmark.
 */
@GameTestHolder(DhTurbo.MOD_ID)
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
        DhTurbo.LOGGER.info("Surface vs vanilla on cell corners: {}", accuracy);
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
        // Forests for the tree count, plus random tiles of every other kind for features that might fail.
        for (int attempt = 0; attempt < 400 && forests < 5; attempt++) {
            int x = (random.nextInt(40_000) - 20_000) & ~63;
            int z = (random.nextInt(40_000) - 20_000) & ~63;
            int y = benchmark.fast().surfaceHeight(x + 32, z + 32, benchmark.fast().seaLevel());
            boolean forest = y > benchmark.fast().seaLevel() && benchmark.fast().biome(x + 32, y, z + 32).is(BiomeTags.IS_FOREST);
            if (!forest && attempt % 10 != 0) {
                continue;
            }
            SurfaceBenchmark.Planted planted = benchmark.plant(x, z, 1);
            failures += planted.failures();
            if (forest) {
                forests++;
                treeColumns += planted.treeColumns();
            }
        }
        DhTurbo.LOGGER.info("Real trees in {} forest tiles: {} tree columns, {} failed features", forests, treeColumns,
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

    /**
     * A client rebuilds the server's generator from its own data packs: the description must resolve and the rebuilt
     * generator must produce exactly the server's terrain.
     */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void worldgenRebuiltFromOwnDataPacks(GameTestHelper helper) {
        RegistryAccess server = helper.getLevel().registryAccess();
        Holder<NoiseGeneratorSettings> settings = server.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        MultiNoiseBiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(server
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        LevelGenInfo info = LevelGenInfo.describe(Level.OVERWORLD, settings, biomes, server).orElseThrow();
        RegistryAccess loaded = WorldgenLoader.get();
        LevelGenInfo.Resolved resolved = info.resolve(loaded);
        if (resolved == null) {
            helper.fail("The worldgen loaded from data packs does not match the server's: " + info);
            return;
        }
        SurfaceSampler original = new SurfaceSampler(server, settings.value(), biomes, SEED);
        SurfaceSampler rebuilt = new SurfaceSampler(loaded, resolved.settings().value(), resolved.biomeSource(), SEED);
        Random random = new Random(3);
        for (int i = 0; i < 200; i++) {
            int x = random.nextInt(200_000) - 100_000;
            int z = random.nextInt(200_000) - 100_000;
            int expected = original.surfaceHeight(x, z, 64);
            int actual = rebuilt.surfaceHeight(x, z, 64);
            if (expected != actual || !original.biome(x, expected, z).is(rebuilt.biome(x, actual, z).unwrapKey().orElseThrow())) {
                helper.fail("Rebuilt generator differs at " + x + " " + z + ": " + expected + " vs " + actual);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void benchmark(GameTestHelper helper) {
        if (System.getenv("DHTURBO_BENCH") != null) {
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
            for (String line : overworldBenchmark(helper.getLevel().registryAccess()).run(6, threads)) {
                DhTurbo.LOGGER.info("[bench] {}", line);
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
