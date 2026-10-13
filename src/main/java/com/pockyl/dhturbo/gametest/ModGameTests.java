package com.pockyl.dhturbo.gametest;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.bench.SurfaceBenchmark;
import com.pockyl.dhturbo.dh.TurboActivity;
import com.pockyl.dhturbo.surface.BiomeLook;
import com.pockyl.dhturbo.surface.GroundCover;
import com.pockyl.dhturbo.surface.RealLakes;
import com.pockyl.dhturbo.surface.SurfaceDetail;
import com.pockyl.dhturbo.surface.SurfaceSampler;
import com.pockyl.dhturbo.world.LevelGenInfo;
import com.pockyl.dhturbo.world.WorldgenLoader;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

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
    public static void debugLineCountsTiles(GameTestHelper helper) {
        long start = TurboActivity.started();
        helper.assertTrue(TurboActivity.debugLine().orElse("").contains("1 running"), "a tile in progress shows as running");
        TurboActivity.finished(start);
        TurboActivity.started();
        TurboActivity.failed();
        String line = TurboActivity.debugLine().orElse("");
        helper.assertTrue(line.startsWith("DH Turbo: ") && line.contains("0 running") && line.contains("failed"),
                "finished and failed tiles are counted: " + line);
        helper.succeed();
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
        realTreesGrowIn(helper, BiomeTags.IS_FOREST, 7);
    }

    /**
     * Jungles place several trees per chunk, often on the same spot: the second one must not grow inside the first
     * one's trunk (no logs, then vanilla's cocoa decorator crashes).
     */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void realTreesGrowInJungles(GameTestHelper helper) {
        realTreesGrowIn(helper, BiomeTags.IS_JUNGLE, 11);
    }

    private static void realTreesGrowIn(GameTestHelper helper, TagKey<Biome> kind, long randomSeed) {
        SurfaceBenchmark benchmark = overworldBenchmark(helper.getLevel().registryAccess());
        Random random = new Random(randomSeed);
        int found = 0;
        int treeColumns = 0;
        long failures = 0;
        // Tiles of the kind for the tree count, plus random tiles of every other kind for features that might fail.
        for (int attempt = 0; attempt < 3000 && found < 5; attempt++) {
            int x = (random.nextInt(40_000) - 20_000) & ~63;
            int z = (random.nextInt(40_000) - 20_000) & ~63;
            int y = benchmark.fast().surfaceHeight(x + 32, z + 32, benchmark.fast().seaLevel());
            boolean wanted = y > benchmark.fast().seaLevel() && benchmark.fast().biome(x + 32, y, z + 32).is(kind);
            if (!wanted && attempt % 100 != 0) {
                continue;
            }
            SurfaceBenchmark.Planted planted = benchmark.plant(x, z, 1);
            failures += planted.failures();
            if (wanted) {
                found++;
                treeColumns += planted.treeColumns();
            }
        }
        DhTurbo.LOGGER.info("Real trees in {} {} tiles: {} tree columns, {} failed features", found, kind.location(),
                treeColumns, failures);
        if (found == 0) {
            helper.fail("No " + kind.location() + " tile found to test trees in");
        } else if (failures > 0) {
            helper.fail(failures + " tree features failed on the virtual level");
        } else if (treeColumns < found * 500) {
            helper.fail("Too few trees: " + treeColumns + " columns in " + found + " tiles");
        }
        helper.succeed();
    }

    /**
     * Surface lava lakes are found, and a lake seen on a coarse tile is at the same place and height on the
     * full-detail tile (every tile asks the same terrain, so tiles agree).
     */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void lavaLakesAgreeAcrossDetail(GameTestHelper helper) {
        SurfaceBenchmark benchmark = overworldBenchmark(helper.getLevel().registryAccess());
        RealLakes lakes = benchmark.lakes();
        int width = SurfaceBenchmark.TILE_WIDTH;
        int spacing = RealLakes.MAX_SPACING;
        int found = 0;
        int checked = 0;
        long start = System.nanoTime();
        for (int tile = 0; tile < 6 && checked < 5; tile++) {
            int minX = tile * width * spacing;
            int minZ = -tile * width * spacing;
            RealLakes.Lake[] coarse = lakes.plan(minX, minZ, spacing, width);
            if (coarse == null) {
                continue;
            }
            for (int index = 0; index < coarse.length && checked < 5; index++) {
                RealLakes.Lake lake = coarse[index];
                if (lake == null) {
                    continue;
                }
                found++;
                int x = minX + index % width * spacing;
                int z = minZ + index / width * spacing;
                int fineX = Math.floorDiv(x, width) * width;
                int fineZ = Math.floorDiv(z, width) * width;
                RealLakes.Lake[] fine = lakes.plan(fineX, fineZ, 1, width);
                RealLakes.Lake same = fine == null ? null : fine[(x - fineX) + (z - fineZ) * width];
                if (same == null || same.top() != lake.top() || !same.fluid().equals(lake.fluid())) {
                    helper.fail("Lake at " + x + " " + z + " differs on the detailed tile: " + lake + " vs " + same);
                    return;
                }
                checked++;
            }
        }
        DhTurbo.LOGGER.info("Lava lakes: {} lake columns on coarse tiles, {} checked on detailed ones, {} ms", found,
                checked, (System.nanoTime() - start) / 1_000_000);
        for (int lakeSpacing : new int[]{1, 4, 16}) {
            long timer = System.nanoTime();
            for (int tile = 0; tile < 8; tile++) {
                lakes.plan(tile * 9_000, 5_000, lakeSpacing, width);
            }
            DhTurbo.LOGGER.info("Lava lakes: {} ms per tile with {}-block columns", (System.nanoTime() - timer) / 8e6,
                    lakeSpacing);
        }
        if (checked == 0) {
            helper.fail("No surface lake found in 6 coarse tiles");
        }
        helper.succeed();
    }

    /** Vanilla keeps grass on steep slopes outside snowy mountains; the walls show the layers under it. */
    @GameTest(template = "empty")
    public static void steepSlopesKeepTheirTop(GameTestHelper helper) {
        var biomes = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        Holder<Biome> plains = biomes.getHolderOrThrow(Biomes.PLAINS);
        Holder<Biome> slopes = biomes.getHolderOrThrow(Biomes.SNOWY_SLOPES);
        BlockState plainsTop = SurfaceDetail.top(plains, BiomeLook.of(plains), SEED, 0, 90, 0, true);
        BlockState slopesTop = SurfaceDetail.top(slopes, BiomeLook.of(slopes), SEED, 0, 150, 0, true);
        BlockState under = SurfaceDetail.subsurface(plainsTop, BiomeLook.of(plains), Blocks.STONE.defaultBlockState());
        if (!plainsTop.is(Blocks.GRASS_BLOCK) || !under.is(Blocks.DIRT)) {
            helper.fail("Steep plains should be grass over dirt: " + plainsTop + " over " + under);
        } else if (!slopesTop.is(Blocks.STONE)) {
            helper.fail("Steep snowy slopes should be stone: " + slopesTop);
        }
        helper.succeed();
    }

    /** Distant ground gets grass and the biome's own flowers, in plausible amounts. */
    @GameTest(template = "empty")
    public static void groundCoverFollowsBiomes(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        var biomes = registries.registryOrThrow(Registries.BIOME);
        Cover flowerForest = cover(biomes.getHolderOrThrow(Biomes.FLOWER_FOREST), Blocks.GRASS_BLOCK);
        Cover plains = cover(biomes.getHolderOrThrow(Biomes.PLAINS), Blocks.GRASS_BLOCK);
        Cover desert = cover(biomes.getHolderOrThrow(Biomes.DESERT), Blocks.SAND);
        DhTurbo.LOGGER.info("Ground cover: flower forest {}, plains {}, desert {}", flowerForest, plains, desert);
        if (flowerForest.flowers() < 0.2 || flowerForest.species() < 4) {
            helper.fail("Flower forest has too few flowers: " + flowerForest);
        } else if (plains.grass() < 0.35 || plains.flowers() > 0.06) {
            helper.fail("Plains cover is off: " + plains);
        } else if (desert.grass() > 0 || desert.flowers() > 0 || desert.other() > 0.05) {
            helper.fail("Desert cover is off: " + desert);
        }
        helper.succeed();
    }

    private record Cover(double flowers, double grass, double other, int species) {
    }

    private static Cover cover(Holder<Biome> biome, Block ground) {
        int samples = 4000;
        int flowers = 0;
        int grass = 0;
        int other = 0;
        Set<Block> species = new HashSet<>();
        Random random = new Random(5);
        for (int i = 0; i < samples; i++) {
            int x = random.nextInt(20_000) - 10_000;
            int z = random.nextInt(20_000) - 10_000;
            BlockState plant = GroundCover.at(SEED, x, 70, z, biome, biome, ground.defaultBlockState());
            if (plant == null) {
                continue;
            }
            if (plant.is(BlockTags.FLOWERS)) {
                flowers++;
                species.add(plant.getBlock());
            } else if (plant.is(Blocks.SHORT_GRASS) || plant.is(Blocks.FERN)) {
                grass++;
            } else {
                other++;
            }
        }
        return new Cover(flowers / (double) samples, grass / (double) samples, other / (double) samples, species.size());
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
