package com.pockyl.dhturbo.gametest;

import it.unimi.dsi.fastutil.ints.Int2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectSortedMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.pockyl.dhturbo.Config;
import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.bench.SurfaceBenchmark;
import com.pockyl.dhturbo.dh.ExactGate;
import com.pockyl.dhturbo.dh.HorizonFill;
import com.pockyl.dhturbo.dh.TurboActivity;
import com.pockyl.dhturbo.network.WorldgenSharing;
import com.pockyl.dhturbo.surface.BiomeLook;
import com.pockyl.dhturbo.surface.ChunkTagReader;
import com.pockyl.dhturbo.surface.ColumnBuilder;
import com.pockyl.dhturbo.surface.ExactChunks;
import com.pockyl.dhturbo.surface.FakeTrees;
import com.pockyl.dhturbo.surface.GroundCover;
import com.pockyl.dhturbo.surface.ProfileTerrain;
import com.pockyl.dhturbo.surface.RealLakes;
import com.pockyl.dhturbo.surface.RealStructures;
import com.pockyl.dhturbo.surface.RealTrees;
import com.pockyl.dhturbo.surface.SavedChunks;
import com.pockyl.dhturbo.surface.StructurePrefetch;
import com.pockyl.dhturbo.surface.SurfaceDetail;
import com.pockyl.dhturbo.surface.SurfaceSampler;
import com.pockyl.dhturbo.surface.SurfaceTile;
import com.pockyl.dhturbo.surface.TerrainCheck;
import com.pockyl.dhturbo.world.LevelGenInfo;
import com.pockyl.dhturbo.world.TerrainSupport;
import com.pockyl.dhturbo.world.WorldgenLoader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.function.UnaryOperator;

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
        helper.assertTrue(TurboActivity.debugLine().contains("1 running"), "a tile in progress shows as running");
        TurboActivity.finished(start);
        TurboActivity.started();
        TurboActivity.failed();
        String line = TurboActivity.debugLine();
        helper.assertTrue(line.startsWith("DH Turbo: ") && line.contains("0 running") && line.contains("failed"),
                "finished and failed tiles are counted: " + line);
        helper.succeed();
    }

    /**
     * The exact pass runs vanilla features on chunks that never enter the game. A vanilla region reports every block to
     * the real level, which would register beds and bells of those chunks as villager points of interest in the world.
     */
    /** The first pass covers the view with a handful of tiles, and a pass only ends when its sections are there. */
    @GameTest(template = "empty")
    public static void horizonStartsCoarseEnough(GameTestHelper helper) {
        for (int diameter : new int[]{1024, 2048, 8192, 16384, 65536}) {
            int level = HorizonFill.firstLevel(diameter);
            int tilesAcross = diameter / (64 << level);
            if (tilesAcross > 6 || tilesAcross < 3) {
                helper.fail("A view of " + diameter + " blocks starts " + tilesAcross + " tiles across, at level " + level);
            }
        }
        long settled = System.nanoTime() - 1_000_000_000L;
        if (HorizonFill.passDone(settled, false, 0, 0) || HorizonFill.passDone(settled, false, 100, 50)) {
            helper.fail("A pass ended with its sections missing");
        } else if (!HorizonFill.passDone(settled, false, 100, 95)) {
            helper.fail("A pass did not end with nearly all sections there");
        } else if (HorizonFill.passDone(System.nanoTime(), false, 100, 100)) {
            helper.fail("A pass ended before its sections could reach the tree");
        } else if (!HorizonFill.passDone(System.nanoTime() - 30_000_000_000L, false, 100, 0)
                || HorizonFill.passDone(System.nanoTime() - 30_000_000_000L, true, 100, 0)) {
            helper.fail("Stuck passes are not given up at the right time");
        }
        helper.succeed();
    }

    /** A preview tile is the real terrain without what grows and stands on it, so swapping it for the real tile moves no land. */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void previewTilesShowTheRealGround(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        ColumnBuilder builder = new ColumnBuilder(overworldGenerator(registries), registries,
                helper.getLevel().dimensionType(), SEED, UnaryOperator.identity(), helper.getLevel().getStructureManager());
        for (int spacing : new int[]{4, 16}) {
            int[] real = new int[64 * 64];
            int[] preview = new int[64 * 64];
            builder.build(0, 0, spacing, 64, false, (i, j, biome, layers) -> real[i + j * 64] = ground(layers));
            builder.build(0, 0, spacing, 64, true, (i, j, biome, layers) -> preview[i + j * 64] = ground(layers));
            int same = 0;
            for (int i = 0; i < real.length; i++) {
                same += Math.abs(real[i] - preview[i]) <= 1 ? 1 : 0;
            }
            DhTurbo.LOGGER.info("Preview tiles, {}-block columns: ground within a block on {} of {} columns", spacing, same, real.length);
            if (same < real.length * 9 / 10) {
                helper.fail("Preview ground differs on " + (real.length - same) + " columns at spacing " + spacing);
            }
        }
        helper.succeed();
    }

    private static NoiseBasedChunkGenerator overworldGenerator(RegistryAccess registries) {
        Holder<NoiseGeneratorSettings> settings = registries.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        return new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(registries
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)), settings);
    }

    /** Top of the highest run of solid, non-plant ground in a column. */
    private static int ground(List<ColumnBuilder.Layer> layers) {
        int top = 0;
        for (ColumnBuilder.Layer layer : layers) {
            BlockState state = layer.state();
            if (!state.isAir() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS) && !state.is(BlockTags.REPLACEABLE)) {
                top = layer.top();
            }
        }
        return top;
    }

    @GameTest(template = "empty")
    public static void exactChunksStayOutOfTheWorld(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null && nether.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator,
                "the nether is a noise level");
        BlockPos pos = new BlockPos(9000, 70, 9000);
        // Points of interest are saved with the world, so a failed run would fail every later one.
        if (nether.getPoiManager().getType(pos).isPresent()) {
            nether.getPoiManager().remove(pos);
        }
        ExactChunks exact = new ExactChunks(nether, (NoiseBasedChunkGenerator) nether.getChunkSource().getGenerator());
        helper.assertTrue(exact.writeForTest(pos, Blocks.BELL.defaultBlockState()), "the region accepts the block");
        // The level hands point of interest changes to the server thread, so look a few ticks later.
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(nether.getPoiManager().getType(pos).isEmpty(), "no point of interest in the real world");
            helper.succeed();
        });
    }

    /** Saved chunks are read as the game wrote them, and leave the running level alone. */
    @GameTest(template = "empty")
    public static void savedChunksKeepTheirBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(0, 2, 0));
        level.setBlock(pos, Blocks.BELL.defaultBlockState(), Block.UPDATE_ALL);
        ChunkAccess chunk = level.getChunk(pos);
        CompoundTag tag = ChunkTagReader.write(level, chunk);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        ChunkAccess read = new SavedChunks(level).decode(chunk.getPos(), tag).orElse(null);
        helper.assertTrue(read != null, "a finished chunk is read");
        helper.assertTrue(read.getBlockState(pos).is(Blocks.BELL), "the block the world saved, not the generator's");
        helper.assertTrue(read.getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2).equals(
                chunk.getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2)), "biomes are read too");
        helper.assertTrue(read.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX() & 15, pos.getZ() & 15) >= pos.getY(),
                "heights are there for Distant Horizons");
        tag.putString("Status", "minecraft:carvers");
        helper.assertTrue(new SavedChunks(level).decode(chunk.getPos(), tag).isEmpty(), "an unfinished chunk is not");
        helper.succeed();
    }

    /** A region file lists its chunks in its first 4 KiB, and a missing file holds none. */
    @GameTest(template = "empty")
    public static void regionHeadersListTheirChunks(GameTestHelper helper) {
        Path file = null;
        try {
            file = Files.createTempFile("dhturbo", ".mca");
            ByteBuffer header = ByteBuffer.allocate(4096);
            header.putInt(4 * (3 + 2 * 32), 0x00000201);
            Files.write(file, header.array());
            BitSet chunks = SavedChunks.header(file);
            helper.assertTrue(chunks.cardinality() == 1 && chunks.get(3 + 2 * 32), "chunk 3 2 of the region: " + chunks);
            Files.delete(file);
            helper.assertTrue(SavedChunks.header(file).isEmpty(), "no file, no chunks");
        } catch (IOException e) {
            helper.fail(e.toString());
        }
        helper.succeed();
    }

    /** The exact pass waits for a busy server and for players on the move, then carries on by itself. */
    @GameTest(template = "empty")
    public static void exactPassKeepsOutOfTheWay(GameTestHelper helper) {
        long second = 1_000_000_000L;
        ExactGate gate = new ExactGate();
        List<int[]> here = List.<int[]>of(new int[]{0, 0});
        helper.assertTrue(gate.hold(10, here, second) == null, "a first look at a quiet server");
        helper.assertTrue(gate.hold(10, List.<int[]>of(new int[]{3, 0}), 2 * second) == null, "walking does not matter");
        helper.assertTrue(ExactGate.SERVER_BUSY.equals(gate.hold(45, List.<int[]>of(new int[]{3, 0}), 3 * second)),
                "a slow tick stops the pass");
        helper.assertTrue(ExactGate.PLAYER_MOVING.equals(gate.hold(10, List.<int[]>of(new int[]{40, 0}), 4 * second)),
                "flying stops the pass");
        helper.assertTrue(ExactGate.PLAYER_MOVING.equals(gate.hold(10, List.<int[]>of(new int[]{40, 0}), 6 * second)),
                "it waits until the player has settled");
        helper.assertTrue(gate.hold(10, List.<int[]>of(new int[]{40, 0}), 8 * second) == null, "then it goes on");
        helper.assertTrue(gate.hold(10, List.<int[]>of(new int[]{4000, 0}, new int[]{0, 0}), 9 * second) == null,
                "a player joining far away is no movement");
        helper.succeed();
    }

    /** A radius covers a circle of batches, not the square around it, and a small radius stays small. */
    @GameTest(template = "empty")
    public static void exactRadiusIsACircle(GameTestHelper helper) {
        helper.assertTrue(ExactGate.reaches(4, 4, 0, 0, 8, 1), "the batch of the player");
        helper.assertFalse(ExactGate.reaches(4, 4, 1, 1, 8, 1), "radius 1 does not reach the next batch");
        helper.assertTrue(ExactGate.reaches(-1, -1, -1, -1, 8, 1), "negative coordinates");
        helper.assertTrue(ExactGate.reaches(4, 4, 2, 0, 8, 12), "12 chunks reach a batch 12 chunks along the axis");
        helper.assertFalse(ExactGate.reaches(4, 4, 2, 2, 8, 12), "but not the corner of that square");
        helper.succeed();
    }

    /** A server with Distant Horizons sends distant terrain, so it has no reason to hand out the seed. */
    @GameTest(template = "empty")
    public static void seedStaysOnServersWithDistantHorizons(GameTestHelper helper) {
        boolean configured = Config.SHARE_WORLDGEN.get();
        try {
            Config.SHARE_WORLDGEN.set(true);
            helper.assertTrue(WorldgenSharing.shares(false), "a server without Distant Horizons shares");
            helper.assertFalse(WorldgenSharing.shares(true), "a server with Distant Horizons does not");
            Config.SHARE_WORLDGEN.set(false);
            helper.assertFalse(WorldgenSharing.shares(false), "the config still decides");
        } finally {
            Config.SHARE_WORLDGEN.set(configured);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void excludedDimensionsGoToDistantHorizons(GameTestHelper helper) {
        List<? extends String> configured = Config.EXCLUDED_DIMENSIONS.get();
        try {
            Config.EXCLUDED_DIMENSIONS.set(List.of("minecraft:the_end"));
            helper.assertTrue(Config.generates(Level.OVERWORLD), "the overworld is still generated");
            helper.assertFalse(Config.generates(Level.END), "an excluded dimension is left to Distant Horizons");
        } finally {
            Config.EXCLUDED_DIMENSIONS.set(configured);
        }
        helper.succeed();
    }

    /**
     * A level is drawn only when this mod reads its terrain right: checked against the level's own generator. A sampler
     * of other noise settings (here amplified terrain against the normal overworld, as a terrain mod the noise settings
     * do not describe would look) fails the check, generators other than noise ones are not read at all, and the F3
     * line names the levels left to Distant Horizons.
     */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void unreadableTerrainIsLeftToDistantHorizons(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        Holder<NoiseGeneratorSettings> overworld = registries.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        Holder<NoiseGeneratorSettings> amplified = registries.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.AMPLIFIED);
        MultiNoiseBiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(registries
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        NoiseBasedChunkGenerator real = new NoiseBasedChunkGenerator(biomes, overworld);
        RandomState realState = RandomState.create(overworld.value(), registries.lookupOrThrow(Registries.NOISE), SEED);
        LevelHeightAccessor heights = LevelHeightAccessor.create(-64, 384);
        long start = System.nanoTime();
        TerrainCheck.Result same = TerrainCheck.run(new SurfaceSampler(registries, overworld.value(), biomes, SEED), null,
                real, realState, heights, SEED);
        long millis = (System.nanoTime() - start) / 1_000_000;
        TerrainCheck.Result other = TerrainCheck.run(new SurfaceSampler(registries, amplified.value(), biomes, SEED), null,
                real, realState, heights, SEED);
        DhTurbo.LOGGER.info("Terrain check: same settings {}/{} ({} ms), amplified {}/{}", same.agreeing(), same.compared(),
                millis, other.agreeing(), other.compared());
        helper.assertTrue(same.passed(), "the overworld's own terrain is read right: " + same);
        helper.assertFalse(other.passed(), "terrain the sampler does not read right is detected: " + other);
        helper.assertTrue(other.reason() != null && other.reason().contains("agree on"), "the reason gives the numbers");

        Holder<NoiseGeneratorSettings> netherSettings = registries.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.NETHER);
        MultiNoiseBiomeSource netherBiomes = MultiNoiseBiomeSource.createFromPreset(registries
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
        SurfaceSampler netherSampler = new SurfaceSampler(registries, netherSettings.value(), netherBiomes, SEED);
        TerrainCheck.Result nether = TerrainCheck.run(netherSampler, new ProfileTerrain(netherSampler),
                new NoiseBasedChunkGenerator(netherBiomes, netherSettings),
                RandomState.create(netherSettings.value(), registries.lookupOrThrow(Registries.NOISE), SEED),
                LevelHeightAccessor.create(0, 256), SEED);
        helper.assertTrue(nether.passed(), "the Nether's caverns are read right: " + nether);

        FlatLevelSource flat = new FlatLevelSource(FlatLevelGeneratorSettings.getDefault(registries.lookupOrThrow(Registries.BIOME),
                registries.lookupOrThrow(Registries.STRUCTURE_SET), registries.lookupOrThrow(Registries.PLACED_FEATURE)));
        helper.assertTrue(TerrainCheck.unreadable(flat) != null, "a flat world's generator is not read");
        helper.assertTrue(TerrainCheck.unreadable(real) == null, "a noise generator is read");

        TerrainSupport.leftToDistantHorizons(Level.END, "test");
        try {
            String line = TurboActivity.debugLine();
            helper.assertTrue(line.contains("DH draws minecraft:the_end"), "the F3 line names the level: " + line);
        } finally {
            TerrainSupport.drawnByUs(Level.END);
        }
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
     * Trees over a column stay what they are: a branch does not grow down to the ground, a bush does not join the crown
     * above it, and a column wider than a trunk shows no trunk.
     */
    @GameTest(template = "empty")
    public static void treeColumnsKeepTheirGaps(GameTestHelper helper) {
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        Int2ObjectSortedMap<BlockState> branch = new Int2ObjectRBTreeMap<>();
        branch.put(5, log);
        branch.put(7, leaves);
        branch.put(8, leaves);
        Int2ObjectSortedMap<BlockState> bush = new Int2ObjectRBTreeMap<>();
        bush.put(0, leaves);
        bush.put(20, leaves);
        Int2ObjectSortedMap<BlockState> trunk = new Int2ObjectRBTreeMap<>();
        for (int y = 0; y < 6; y++) {
            trunk.put(y, log);
        }
        helper.assertTrue(RealTrees.canopy(branch, true).spans().equals(
                        List.of(new FakeTrees.Span(log, 5, 6), new FakeTrees.Span(leaves, 7, 9))),
                "A branch must hang in the air: " + RealTrees.canopy(branch, true));
        helper.assertTrue(RealTrees.canopy(bush, true).spans().equals(
                        List.of(new FakeTrees.Span(leaves, 0, 1), new FakeTrees.Span(leaves, 20, 21))),
                "Leaves must not fill the gap to the crown above: " + RealTrees.canopy(bush, true));
        helper.assertTrue(RealTrees.canopy(branch, false).spans().equals(List.of(new FakeTrees.Span(leaves, 7, 9))),
                "Wide columns show no wood under the crown: " + RealTrees.canopy(branch, false));
        helper.assertTrue(RealTrees.canopy(trunk, false) == null, "A wide column must not be a trunk");

        Holder<Biome> forest = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME)
                .getHolderOrThrow(Biomes.FOREST);
        int crowns = 0;
        for (int spacing : new int[] {4, 8, 16}) {
            for (int n = 0; n < 4096; n++) {
                FakeTrees.Canopy canopy = FakeTrees.at(1234, (n & 63) * spacing, (n >> 6) * spacing, spacing, BiomeLook.of(forest));
                if (canopy != null) {
                    crowns++;
                    helper.assertTrue(canopy.spans().size() == 1 && canopy.spans().get(0).from() > 0,
                            "Approximate trees on wide columns must stand off the ground: " + canopy);
                }
            }
        }
        helper.assertTrue(crowns > 1000, "Too few approximate trees: " + crowns);
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

    /**
     * Structures are found and built on the virtual level without failing pieces; villages reshape the ground under
     * their houses; and the distant terrain columns carry the structures' blocks at their heights.
     */
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void structuresStandOnDistantTerrain(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        Holder<NoiseGeneratorSettings> settings = registries.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        MultiNoiseBiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(registries
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(biomes, settings);
        SurfaceSampler sampler = new SurfaceSampler(registries, settings.value(), biomes, SEED);
        RealStructures structures = new RealStructures(generator, sampler, helper.getLevel().getStructureManager(), SEED,
                registries, helper.getLevel().dimensionType());
        long start = System.nanoTime();
        List<RealStructures.Built> found = structures.around(-2000, -2000, 2000, 2000);
        long failures = structures.takeFailures();
        Set<String> kinds = new HashSet<>();
        RealStructures.Built village = null;
        for (RealStructures.Built built : found) {
            kinds.add(built.name());
            if (village == null && built.name().contains("village") && built.adaptedGround()) {
                village = built;
            }
        }
        DhTurbo.LOGGER.info("Structures in 4x4 km: {} ({}), {} failed pieces, {} ms ({})", found.size(), kinds, failures,
                (System.nanoTime() - start) / 1_000_000, structures.timings());
        if (failures > 0) {
            helper.fail(failures + " structure pieces failed on the virtual level");
            return;
        }
        if (found.size() < 5 || village == null) {
            helper.fail("Too few structures or no village with adapted ground: " + kinds);
            return;
        }
        // Terrain columns over the village carry its blocks: the exact build at full detail, the coarse one on wide
        // columns (each from a fresh builder, as a tile would build them).
        int minY = sampler.minY();
        int width = SurfaceBenchmark.TILE_WIDTH;
        for (int spacing : new int[]{1, 8}) {
            ColumnBuilder builder = new ColumnBuilder(generator, registries, helper.getLevel().dimensionType(), SEED,
                    UnaryOperator.identity(), helper.getLevel().getStructureManager());
            RealStructures.Built target = same(builder.realStructures().around(village.minX(), village.minZ(),
                    village.minX() + village.sizeX() - 1, village.minZ() + village.sizeZ() - 1,
                    spacing <= RealStructures.FINE_MAX_SPACING), village);
            if (target == null) {
                helper.fail("The village was not found again at " + spacing + "-block spacing");
                return;
            }
            int tileX = Math.floorDiv(target.minX() + target.sizeX() / 2, width * spacing) * width * spacing;
            int tileZ = Math.floorDiv(target.minZ() + target.sizeZ() / 2, width * spacing) * width * spacing;
            int[] counts = new int[2];
            RealStructures.Built shown = target;
            builder.build(tileX, tileZ, spacing, width, (i, j, biome, layers) -> {
                RealStructures.Column column = RealStructures.columnFor(List.of(shown), tileX + i * spacing,
                        tileZ + j * spacing, spacing);
                if (column == null || column.top() == Integer.MIN_VALUE) {
                    return;
                }
                counts[0]++;
                int y = column.top() - 1 - minY;
                BlockState expected = null;
                for (int k = 0; k < column.states().length; k++) {
                    if (column.bottoms()[k] <= column.top() - 1 && column.tops()[k] >= column.top()) {
                        expected = column.states()[k];
                    }
                }
                for (ColumnBuilder.Layer layer : layers) {
                    if (layer.bottom() <= y && layer.top() > y && layer.state() == expected) {
                        counts[1]++;
                        return;
                    }
                }
            });
            DhTurbo.LOGGER.info("Structure columns at {}-block spacing: {} of {} show the structure's top block", spacing,
                    counts[1], counts[0]);
            if (counts[0] == 0 || counts[1] < counts[0]) {
                helper.fail("Village blocks missing from " + spacing + "-block columns: " + counts[1] + " of " + counts[0]);
                return;
            }
        }
        helper.succeed();
    }

    /** The structure of the list with the same name and start chunk, or null. */
    private static RealStructures.Built same(List<RealStructures.Built> list, RealStructures.Built structure) {
        for (RealStructures.Built built : list) {
            if (built.name().equals(structure.name()) && built.startChunk().equals(structure.startChunk())) {
                return built;
            }
        }
        return null;
    }

    private static RealStructures overworldStructures(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        Holder<NoiseGeneratorSettings> settings = registries.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        MultiNoiseBiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(registries
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        return new RealStructures(new NoiseBasedChunkGenerator(biomes, settings),
                new SurfaceSampler(registries, settings.value(), biomes, SEED), helper.getLevel().getStructureManager(),
                SEED, registries, helper.getLevel().dimensionType());
    }

    /**
     * The coarse builds wide tiles use stand where the exact ones do: same structures and boxes, and on every 4th
     * column (what a 4-block tile samples) the same top within 2 blocks almost everywhere.
     */
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void coarseStructuresFollowExactOnes(GameTestHelper helper) {
        List<RealStructures.Built> exact = overworldStructures(helper).around(-1500, -1500, 1500, 1500, true);
        List<RealStructures.Built> coarse = overworldStructures(helper).around(-1500, -1500, 1500, 1500, false);
        int columns = 0;
        int close = 0;
        for (RealStructures.Built built : exact) {
            RealStructures.Built rough = same(coarse, built);
            if (rough == null || !rough.box().equals(built.box())) {
                helper.fail("Coarse build differs from the exact one: " + built.name() + " at " + built.box());
                return;
            }
            for (int x = Math.floorDiv(built.minX() + 3, 4) * 4; x < built.minX() + built.sizeX(); x += 4) {
                for (int z = Math.floorDiv(built.minZ() + 3, 4) * 4; z < built.minZ() + built.sizeZ(); z += 4) {
                    RealStructures.Column fine = built.columnAt(x, z);
                    RealStructures.Column wide = rough.columnAt(x, z);
                    if (fine == null || fine.top() == Integer.MIN_VALUE) {
                        continue;
                    }
                    columns++;
                    if (wide != null && Math.abs(wide.top() - fine.top()) <= 2) {
                        close++;
                    }
                }
            }
        }
        DhTurbo.LOGGER.info("Coarse structures: {} of {} sampled columns within 2 blocks of the exact build ({} structures)",
                close, columns, exact.size());
        if (exact.size() != coarse.size() || columns == 0 || close < columns * 0.85) {
            helper.fail("Coarse builds stray from the exact ones: " + close + " of " + columns + " columns, "
                    + coarse.size() + " vs " + exact.size() + " structures");
            return;
        }
        helper.succeed();
    }

    /** While the workers are idle, the structures around a player are found and built before any tile asks. */
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void structuresPreparedWhileIdle(GameTestHelper helper) {
        RealStructures structures = overworldStructures(helper);
        RealStructures.Built village = null;
        for (RealStructures.Built built : overworldStructures(helper).around(-2000, -2000, 2000, 2000, false)) {
            if (village == null && built.name().contains("village")) {
                village = built;
            }
        }
        if (village == null) {
            helper.fail("No village to prepare");
            return;
        }
        int x = village.minX() + village.sizeX() / 2;
        int z = village.minZ() + village.sizeZ() / 2;
        int areaX = Math.floorDiv(x, 256) * 256;
        int areaZ = Math.floorDiv(z, 256) * 256;
        if (structures.prepared(areaX, areaZ, areaX + 255, areaZ + 255)) {
            helper.fail("Structures were prepared before the prefetch started");
            return;
        }
        ForkJoinPool idleWorkers = new ForkJoinPool(2);
        StructurePrefetch prefetch = new StructurePrefetch(structures, () -> List.of(new int[]{x, z}), idleWorkers);
        helper.onEachTick(() -> {
            // Distant Horizons is asking for tiles: prefetching only runs then.
            prefetch.requested(0, 1);
            if (structures.prepared(areaX, areaZ, areaX + 255, areaZ + 255)) {
                prefetch.close();
                idleWorkers.shutdown();
                helper.succeed();
            }
        });
    }

    /**
     * The Nether is built from whole column profiles: bedrock floor and roof, a lava sea, open caverns between, the
     * biomes' blocks, and its fortresses and bastions.
     */
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void netherHasCavernsAndFortresses(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        Holder<NoiseGeneratorSettings> settings = registries.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.NETHER);
        MultiNoiseBiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(registries
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER));
        DimensionType nether = registries.registryOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.NETHER);
        ColumnBuilder builder = new ColumnBuilder(new NoiseBasedChunkGenerator(biomes, settings), registries, nether, SEED,
                UnaryOperator.identity(), helper.getLevel().getStructureManager());
        Set<Block> blocks = new HashSet<>();
        int[] counts = new int[4];
        long start = System.nanoTime();
        for (int spacing : new int[]{1, 4, 16}) {
            builder.build(spacing * 64, -spacing * 128, spacing, 64, (i, j, biome, layers) -> {
                counts[0]++;
                boolean lava = false;
                boolean cavern = false;
                for (ColumnBuilder.Layer layer : layers) {
                    blocks.add(layer.state().getBlock());
                    lava |= layer.state().is(Blocks.LAVA) && layer.top() == 32;
                    cavern |= layer.state().isAir() && layer.bottom() > 0 && layer.top() < 127;
                }
                counts[1] += layers.get(0).state().is(Blocks.BEDROCK) ? 1 : 0;
                counts[2] += lava ? 1 : 0;
                counts[3] += cavern ? 1 : 0;
            });
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        RealStructures structures = builder.realStructures();
        Set<String> kinds = new HashSet<>();
        if (structures != null) {
            for (RealStructures.Built built : structures.around(-1500, -1500, 1500, 1500)) {
                kinds.add(built.name());
            }
        }
        long failures = structures != null ? structures.takeFailures() : 0;
        DhTurbo.LOGGER.info("Nether: {} columns ({} ms), bedrock floor {}, lava sea {}, caverns {}, blocks {}, structures {}, {} failed",
                counts[0], millis, counts[1], counts[2], counts[3], blocks.size(), kinds, failures);
        if (counts[1] < counts[0] || counts[2] < counts[0] / 10 || counts[3] < counts[0] / 4) {
            helper.fail("Nether columns lack floor, lava or caverns: " + java.util.Arrays.toString(counts));
        } else if (!blocks.contains(Blocks.NETHERRACK) || blocks.size() < 6) {
            helper.fail("Nether surface rules missing: " + blocks);
        } else if (!kinds.contains("minecraft:fortress") || !kinds.contains("minecraft:bastion_remnant") || failures > 0) {
            helper.fail("Nether structures missing or failing: " + kinds + ", " + failures + " failed");
        }
        helper.succeed();
    }

    /** The features of worlds without one surface run on the virtual level: chorus in the End, fungi in the Nether. */
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void profileWorldsGrowTheirPlants(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        ColumnBuilder end = new ColumnBuilder(new NoiseBasedChunkGenerator(TheEndBiomeSource.create(registries.lookupOrThrow(Registries.BIOME)),
                registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.END)), registries,
                registries.registryOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END), SEED, UnaryOperator.identity(), null);
        ColumnBuilder nether = new ColumnBuilder(new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(registries
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER)),
                registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.NETHER)), registries,
                registries.registryOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.NETHER), SEED, UnaryOperator.identity(), null);
        Set<Block> grown = new HashSet<>();
        long failures = 0;
        long endNanos = 0;
        long netherNanos = 0;
        for (int tile = 0; tile < 16; tile++) {
            int x = 1024 + tile % 4 * 256;
            int z = 1024 + tile / 4 * 256;
            long start = System.nanoTime();
            failures += end.build(x, z, 4, 64, (i, j, biome, layers) -> layers.forEach(layer -> grown.add(layer.state().getBlock())));
            long middle = System.nanoTime();
            failures += nether.build(x, z, 2, 64, (i, j, biome, layers) -> layers.forEach(layer -> grown.add(layer.state().getBlock())));
            endNanos += middle - start;
            netherNanos += System.nanoTime() - middle;
        }
        DhTurbo.LOGGER.info("Features in the End and the Nether: {} kinds of block, {} failed, {} ms per End tile, {} ms per Nether tile",
                grown.size(), failures, endNanos / 16_000_000, netherNanos / 16_000_000);
        if (failures > 0) {
            helper.fail(failures + " features failed");
        } else if (!grown.contains(Blocks.CHORUS_PLANT)) {
            helper.fail("No chorus on distant End islands");
        } else if (!grown.contains(Blocks.CRIMSON_STEM) && !grown.contains(Blocks.WARPED_STEM) && !grown.contains(Blocks.BASALT)) {
            helper.fail("No fungi or basalt in the distant Nether: " + grown);
        }
        helper.succeed();
    }

    /** End islands float: end stone with open space under it, not pillars down to the bottom of the world. */
    @GameTest(template = "empty", timeoutTicks = 6000)
    public static void endIslandsFloat(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        Holder<NoiseGeneratorSettings> settings = registries.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(NoiseGeneratorSettings.END);
        TheEndBiomeSource biomes = TheEndBiomeSource.create(registries.lookupOrThrow(Registries.BIOME));
        DimensionType end = registries.registryOrThrow(Registries.DIMENSION_TYPE).getOrThrow(BuiltinDimensionTypes.END);
        ColumnBuilder builder = new ColumnBuilder(new NoiseBasedChunkGenerator(biomes, settings), registries, end, SEED,
                UnaryOperator.identity(), helper.getLevel().getStructureManager());
        int[] counts = new int[2];
        builder.build(-32, -32, 1, 64, (i, j, biome, layers) -> {
            boolean stone = false;
            for (ColumnBuilder.Layer layer : layers) {
                stone |= layer.state().is(Blocks.END_STONE);
            }
            if (stone) {
                counts[0]++;
                counts[1] += layers.get(0).state().isAir() ? 1 : 0;
            }
        });
        Set<String> kinds = new HashSet<>();
        RealStructures structures = builder.realStructures();
        if (structures != null) {
            for (RealStructures.Built built : structures.around(1000, 1000, 3000, 3000)) {
                kinds.add(built.name());
            }
        }
        DhTurbo.LOGGER.info("End: {} island columns at the centre, {} with open space under them, structures {}",
                counts[0], counts[1], kinds);
        if (counts[0] < 1000 || counts[1] < counts[0]) {
            helper.fail("End islands do not float: " + counts[1] + " of " + counts[0] + " columns open below");
        } else if (!kinds.contains("minecraft:end_city")) {
            helper.fail("No end city found: " + kinds);
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
            } else if (plant.is(Blocks.GRASS) || plant.is(Blocks.FERN)) {
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
        // Structures from the client's own templates land where the server's do.
        if (WorldgenLoader.templates() == null) {
            helper.fail("Structure templates cannot be read from the data packs");
            return;
        }
        RealStructures serverStructures = new RealStructures(new NoiseBasedChunkGenerator(biomes, settings), original,
                helper.getLevel().getStructureManager(), SEED, server, helper.getLevel().dimensionType());
        RealStructures clientStructures = new RealStructures(resolved.generator(), rebuilt, WorldgenLoader.templates(), SEED,
                loaded, helper.getLevel().dimensionType());
        Set<String> expected = new HashSet<>();
        for (RealStructures.Built built : serverStructures.around(-800, -800, 800, 800)) {
            expected.add(built.name() + " " + built.box());
        }
        Set<String> actual = new HashSet<>();
        for (RealStructures.Built built : clientStructures.around(-800, -800, 800, 800)) {
            actual.add(built.name() + " " + built.box());
        }
        if (expected.isEmpty() || !expected.equals(actual)) {
            helper.fail("Structures built from the client's data differ: " + expected + " vs " + actual);
            return;
        }
        helper.succeed();
    }

    /** Terrain and biomes share column caches: not a single biome may come out different from the game's sampler. */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void sharedColumnCachesKeepTheBiomes(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        NoiseGeneratorSettings settings = registries.registryOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        MultiNoiseBiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(registries
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        SurfaceSampler sampler = new SurfaceSampler(registries, settings, biomes, SEED);
        Random random = new Random(7);
        for (int i = 0; i < 4000; i++) {
            int x = random.nextInt(200_000) - 100_000;
            int z = random.nextInt(200_000) - 100_000;
            // Probing the terrain first fills the caches the biome then reads.
            int height = sampler.surfaceHeight(x & ~3, z & ~3, 64);
            int quartX = x >> 2;
            int quartY = height >> 2;
            int quartZ = z >> 2;
            Holder<Biome> expected = biomes.getNoiseBiome(quartX, quartY, quartZ, sampler.randomState().sampler());
            if (sampler.noiseBiome(quartX, quartY, quartZ) != expected) {
                helper.fail("Biome differs from the game's sampler at quart " + quartX + " " + quartY + " " + quartZ);
                return;
            }
        }
        helper.succeed();
    }

    /** Columns a cell or more apart stand on cell corners: each must have its own search's height, not a lerp. */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void wideColumnsKeepTheirOwnHeight(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        NoiseGeneratorSettings settings = registries.registryOrThrow(Registries.NOISE_SETTINGS)
                .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        MultiNoiseBiomeSource biomes = MultiNoiseBiomeSource.createFromPreset(registries
                .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        SurfaceSampler sampler = new SurfaceSampler(registries, settings, biomes, SEED);
        SurfaceTile tile = SurfaceTile.generate(sampler, 4096, -8192, 8, 32, 1, false);
        for (int j = 0; j < tile.width; j++) {
            for (int i = 0; i < tile.width; i++) {
                int height = tile.height(i, j);
                if (sampler.surfaceHeight(tile.minX + i * 8, tile.minZ + j * 8, height) != height) {
                    helper.fail("Column " + i + " " + j + " is not its own search's height");
                    return;
                }
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
