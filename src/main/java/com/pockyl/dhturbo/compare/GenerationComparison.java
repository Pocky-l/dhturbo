package com.pockyl.dhturbo.compare;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.surface.ColumnBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * Developer tool: compares the columns this mod builds with chunks the game really generates (terrain, surface rules,
 * features, snow), column by column, and writes a report.
 * <p>
 * Runs on a dedicated server started with {@code -Ddhturbo.compare=<areas>} (Gradle: {@code runCompareServer}, a
 * normal world with a fixed seed in {@code run/compare/}). Each area is 2x2 chunks at a random place within 20000
 * blocks of the origin. With {@code -Ddhturbo.compare.exit=true} the server stops when the report is written.
 */
public final class GenerationComparison {
    private static final int AREA_CHUNKS = 2;
    private static final int RANGE = 20_000;
    private static final int EXAMPLES = 6;

    private GenerationComparison() {
    }

    public static void registerIfRequested() {
        if (System.getProperty(DhTurbo.MOD_ID + ".compare") != null) {
            NeoForge.EVENT_BUS.register(GenerationComparison.class);
        }
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        int areas = Integer.getInteger(DhTurbo.MOD_ID + ".compare", 48);
        try {
            List<String> report = compare(server.overworld(), areas);
            Path file = server.getServerDirectory().resolve("dhturbo-compare.txt");
            Files.write(file, report);
            report.forEach(line -> DhTurbo.LOGGER.info("[compare] {}", line));
            DhTurbo.LOGGER.info("[compare] report written to {}", file.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            DhTurbo.LOGGER.error("[compare] comparison failed", e);
        }
        if (Boolean.getBoolean(DhTurbo.MOD_ID + ".compare.exit")) {
            server.halt(false);
        }
    }

    /**
     * What a column shows from above: the kind of its top block, whether it looks snowy (a snow layer on it, or snow
     * as the block), height, plant and trees.
     */
    private record Surface(String kind, Block block, int height, boolean tree, boolean plant, boolean snowLayer) {
        boolean white() {
            return snowLayer || kind.equals("snow");
        }
    }

    private static List<String> compare(ServerLevel level, int areas) {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)) {
            return List.of("The overworld does not use a noise generator: nothing to compare");
        }
        ColumnBuilder builder = new ColumnBuilder(generator, level.registryAccess(), level.dimensionType(),
                level.getSeed(), UnaryOperator.identity());
        int minY = level.getMinBuildHeight();
        int[] spacings = java.util.Arrays.stream(System.getProperty(DhTurbo.MOD_ID + ".compare.spacings", "1,4").split(","))
                .mapToInt(value -> Integer.parseInt(value.trim())).toArray();
        Stats[] stats = new Stats[spacings.length];
        for (int s = 0; s < spacings.length; s++) {
            stats[s] = new Stats();
        }
        Random random = new Random(level.getSeed() ^ 0xC0FFEE);
        long start = System.nanoTime();
        for (int area = 0; area < areas; area++) {
            int chunkX = (random.nextInt(2 * RANGE) - RANGE) >> 4;
            int chunkZ = (random.nextInt(2 * RANGE) - RANGE) >> 4;
            ChunkAccess[] chunks = new ChunkAccess[AREA_CHUNKS * AREA_CHUNKS];
            for (int dx = 0; dx < AREA_CHUNKS; dx++) {
                for (int dz = 0; dz < AREA_CHUNKS; dz++) {
                    chunks[dx + dz * AREA_CHUNKS] = level.getChunk(chunkX + dx, chunkZ + dz);
                }
            }
            int minX = chunkX << 4;
            int minZ = chunkZ << 4;
            for (int s = 0; s < spacings.length; s++) {
                int spacing = spacings[s];
                Stats sink = stats[s];
                // Wider columns are compared at their sample points (the block at the column's corner).
                builder.build(minX, minZ, spacing, AREA_CHUNKS * 16 / spacing, (i, j, biome, layers) -> {
                    int x = minX + i * spacing;
                    int z = minZ + j * spacing;
                    ChunkAccess chunk = chunks[((x - minX) >> 4) + ((z - minZ) >> 4) * AREA_CHUNKS];
                    Surface real = real(level, chunk, x, z);
                    Surface ours = ours(layers, minY);
                    Holder<Biome> realBiome = level.getBiome(new BlockPos(x, real.height, z));
                    int base = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level,
                            level.getChunkSource().randomState());
                    sink.add(x, z, real, ours, realBiome, biome, base);
                });
            }
        }
        List<String> report = new ArrayList<>();
        // -Ddhturbo.compare.probe=x,z;x,z prints the real blocks around the surface of those columns.
        for (String probe : System.getProperty(DhTurbo.MOD_ID + ".compare.probe", "").split(";")) {
            if (probe.isBlank()) {
                continue;
            }
            String[] xz = probe.split(",");
            int x = Integer.parseInt(xz[0].trim());
            int z = Integer.parseInt(xz[1].trim());
            ChunkAccess chunk = level.getChunk(x >> 4, z >> 4);
            int height = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
            StringBuilder line = new StringBuilder("Probe " + x + " " + z + ":");
            for (int y = height + 3; y >= height - 3; y--) {
                BlockPos pos = new BlockPos(x, y, z);
                line.append(' ').append(y).append('=').append(Stats.id(chunk.getBlockState(pos).getBlock()));
            }
            BlockPos at = new BlockPos(x, height, z);
            line.append(String.format(Locale.ROOT, " | biome %s, cold %b, block light %d, sky light %d",
                    Stats.name(level.getBiome(at)), level.getBiome(at).value().coldEnoughToSnow(at),
                    level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at),
                    level.getBrightness(net.minecraft.world.level.LightLayer.SKY, at)));
            stats[0].probes.add(line.toString());
            // Density on the cell corners around the surface: vanilla's router as is, vanilla's router through our
            // column caches, and the sampler (caves stripped, interpolated value).
            net.minecraft.world.level.levelgen.DensityFunction vanilla = level.getChunkSource().randomState().router().finalDensity();
            net.minecraft.world.level.levelgen.DensityFunction cached = com.pockyl.dhturbo.surface.SurfaceSampler.columnCached(vanilla);
            StringBuilder densities = new StringBuilder("  densities (vanilla / vanilla cached / ours):");
            int cell = builder.sampler().cellHeight();
            for (int y = Math.floorDiv(height - 24 - minY, cell) * cell + minY; y <= height + 16; y += cell) {
                var point = new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(x, y, z);
                densities.append(String.format(Locale.ROOT, " y%d %.3f/%.3f/%.3f", y, vanilla.compute(point),
                        cached.compute(point), builder.sampler().density(x, y, z)));
            }
            densities.append(" | vanilla height ").append(generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG,
                    level, level.getChunkSource().randomState())).append(", ours ")
                    .append(builder.sampler().surfaceHeight(x, z, height));
            stats[0].probes.add(densities.toString());
        }
        report.add(String.format(Locale.ROOT, "Seed %d, %d areas of %dx%d chunks, %.1f s", level.getSeed(), areas,
                AREA_CHUNKS, AREA_CHUNKS, (System.nanoTime() - start) / 1e9));
        for (int s = 0; s < spacings.length; s++) {
            report.add("");
            report.add(String.format(Locale.ROOT, "===== Columns %d block(s) wide: %d columns =====", spacings[s],
                    stats[s].columns));
            stats[s].report(report);
        }
        return report;
    }

    private static Surface real(ServerLevel level, ChunkAccess chunk, int x, int z) {
        int height = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, height - 1, z);
        BlockState top = chunk.getBlockState(pos);
        BlockState above = chunk.getBlockState(pos.setY(height));
        int surfaceTop = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) + 1;
        if (above.is(Blocks.POWDER_SNOW)) {
            // Powder snow has no collision, so motion heightmaps skip it; from above it is the snowy top.
            int powderTop = height;
            while (chunk.getBlockState(pos.setY(powderTop)).is(Blocks.POWDER_SNOW)) {
                powderTop++;
            }
            return new Surface("snow", Blocks.POWDER_SNOW, powderTop, false, false, false);
        }
        boolean tree = top.is(BlockTags.LOGS) || surfaceTop > height + 1
                && chunk.getBlockState(pos.setY(surfaceTop - 1)).is(BlockTags.LEAVES);
        boolean snow = above.is(Blocks.SNOW);
        boolean plant = !above.isAir() && !snow && !above.liquid();
        return new Surface(kind(top), top.getBlock(), height, tree, plant, snow);
    }

    /** Our column seen the same way: canopy (leaves, logs) and plants do not count as ground. */
    private static Surface ours(List<ColumnBuilder.Layer> layers, int minY) {
        boolean tree = false;
        boolean plant = false;
        boolean snow = false;
        for (int index = layers.size() - 1; index >= 0; index--) {
            ColumnBuilder.Layer layer = layers.get(index);
            BlockState state = layer.state();
            if (state.isAir()) {
                continue;
            }
            if (state.is(Blocks.SNOW)) {
                snow = true;
                continue;
            }
            if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
                tree = true;
                continue;
            }
            if (!state.blocksMotion() && !state.liquid()) {
                plant = true;
                continue;
            }
            return new Surface(kind(state), state.getBlock(), layer.top() + minY, tree, plant, snow && !tree);
        }
        return new Surface("void", Blocks.AIR, minY, tree, plant, false);
    }

    private static String kind(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.SNOW_BLOCK || block == Blocks.POWDER_SNOW || block == Blocks.SNOW) {
            return "snow";
        }
        if (block == Blocks.ICE || block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE) {
            return "ice";
        }
        if (block == Blocks.WATER || block == Blocks.BUBBLE_COLUMN) {
            return "water";
        }
        if (block == Blocks.LAVA) {
            return "lava";
        }
        if (block == Blocks.GRASS_BLOCK) {
            return "grass";
        }
        if (block == Blocks.SAND || block == Blocks.RED_SAND || block == Blocks.SUSPICIOUS_SAND) {
            return "sand";
        }
        if (block == Blocks.SANDSTONE || block == Blocks.RED_SANDSTONE) {
            return "sandstone";
        }
        if (state.is(BlockTags.TERRACOTTA)) {
            return "terracotta";
        }
        if (block == Blocks.GRAVEL || block == Blocks.SUSPICIOUS_GRAVEL) {
            return "gravel";
        }
        if (block == Blocks.DIRT || block == Blocks.COARSE_DIRT || block == Blocks.PODZOL || block == Blocks.ROOTED_DIRT
                || block == Blocks.MYCELIUM || block == Blocks.MUD || block == Blocks.DIRT_PATH || block == Blocks.CLAY
                || block == Blocks.MOSS_BLOCK || block == Blocks.MUDDY_MANGROVE_ROOTS) {
            return "dirt";
        }
        if (state.is(BlockTags.BASE_STONE_OVERWORLD) || block == Blocks.CALCITE || block == Blocks.STONE
                || block == Blocks.COBBLESTONE || block == Blocks.MOSSY_COBBLESTONE || state.is(BlockTags.COAL_ORES)
                || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES)) {
            return "stone";
        }
        return "other";
    }

    /** Counters and examples. */
    private static final class Stats {
        int columns;
        int compared;
        int bothTrees;
        int realTreeOnly;
        int oursTreeOnly;
        int plantsBoth;
        int plantsRealOnly;
        int plantsOursOnly;
        int biomeMismatch;
        final int[] heightErrors = new int[8];
        /** Ours minus real, -5..+5 (clamped), and by real height band: [below 60, 60-66, 67-90, above 90] x [exact, ours higher, ours lower]. */
        final int[] signedErrors = new int[11];
        final int[][] bands = new int[4][3];
        int landColumns;
        int baseIsReal;
        int baseIsOurs;
        final List<String> heightExamples = new ArrayList<>();
        final Map<String, Integer> kinds = new TreeMap<>();
        final Map<String, Integer> blocks = new TreeMap<>();
        final Map<String, List<String>> examples = new TreeMap<>();
        int snowLayerBoth;
        int snowLayerRealOnly;
        int snowLayerOursOnly;
        /** Per real biome: [columns, looks snowy in both, only real, only ours]. */
        final Map<String, int[]> snowByBiome = new TreeMap<>();
        final List<String> probes = new ArrayList<>();

        void add(int x, int z, Surface real, Surface ours, Holder<Biome> realBiome, Holder<Biome> ourBiome, int base) {
            columns++;
            String biomeName = name(realBiome);
            if (!biomeName.equals(name(ourBiome))) {
                biomeMismatch++;
            }
            if (real.tree && ours.tree) {
                bothTrees++;
            } else if (real.tree) {
                realTreeOnly++;
            } else if (ours.tree) {
                oursTreeOnly++;
            }
            if (real.plant && ours.plant) {
                plantsBoth++;
            } else if (real.plant) {
                plantsRealOnly++;
            } else if (ours.plant) {
                plantsOursOnly++;
            }
            if (real.tree || ours.tree) {
                // Under trees the ground is hidden; heights there measure the canopy.
                return;
            }
            compared++;
            int error = Math.min(heightErrors.length - 1, Math.abs(real.height - ours.height));
            heightErrors[error]++;
            int signed = ours.height - real.height;
            signedErrors[Math.clamp(signed, -5, 5) + 5]++;
            if (real.height > 66 && !real.kind.equals("water")) {
                landColumns++;
                if (base == real.height) {
                    baseIsReal++;
                }
                if (base == ours.height) {
                    baseIsOurs++;
                }
                if (heightExamples.size() < 12 && ours.height != real.height) {
                    heightExamples.add(String.format(Locale.ROOT, "%d %d: real %d, vanilla noise height %d, ours %d",
                            x, z, real.height, base, ours.height));
                }
            }
            int band = real.height < 60 ? 0 : real.height <= 66 ? 1 : real.height <= 90 ? 2 : 3;
            bands[band][signed == 0 ? 0 : signed > 0 ? 1 : 2]++;
            String pair = real.kind + " -> " + ours.kind;
            kinds.merge(pair, 1, Integer::sum);
            if (!real.kind.equals(ours.kind)) {
                blocks.merge(id(real.block) + " -> " + id(ours.block), 1, Integer::sum);
                List<String> list = examples.computeIfAbsent(pair, key -> new ArrayList<>());
                if (list.size() < EXAMPLES) {
                    list.add(String.format(Locale.ROOT, "%d %d %d (ours y %d), real biome %s, ours %s", x, real.height,
                            z, ours.height, biomeName, name(ourBiome)));
                }
            }
            if (real.snowLayer && ours.snowLayer) {
                snowLayerBoth++;
            } else if (real.snowLayer) {
                snowLayerRealOnly++;
            } else if (ours.snowLayer) {
                snowLayerOursOnly++;
            }
            int[] snow = snowByBiome.computeIfAbsent(biomeName, key -> new int[4]);
            snow[0]++;
            boolean realSnow = real.white();
            boolean ourSnow = ours.white();
            if (realSnow && ourSnow) {
                snow[1]++;
            } else if (realSnow) {
                snow[2]++;
            } else if (ourSnow) {
                snow[3]++;
            }
        }

        void report(List<String> out) {
            out.addAll(probes);
            int same = 0;
            for (Map.Entry<String, Integer> entry : kinds.entrySet()) {
                String[] parts = entry.getKey().split(" -> ");
                if (parts[0].equals(parts[1])) {
                    same += entry.getValue();
                }
            }
            out.add(String.format(Locale.ROOT, "Ground compared on %d columns (no trees): top kind matches %.1f%%, "
                    + "biome differs on %.1f%% of all columns", compared, pct(same, compared), pct(biomeMismatch, columns)));
            StringBuilder heights = new StringBuilder("Height error: ");
            for (int i = 0; i < heightErrors.length; i++) {
                heights.append(i == heightErrors.length - 1 ? ">=" : "").append(i).append(": ")
                        .append(String.format(Locale.ROOT, "%.1f%%  ", pct(heightErrors[i], compared)));
            }
            out.add(heights.toString());
            StringBuilder signedLine = new StringBuilder("Ours minus real: ");
            for (int i = 0; i < signedErrors.length; i++) {
                signedLine.append(i == 0 ? "<=" : i == signedErrors.length - 1 ? ">=" : "").append(i - 5).append(": ")
                        .append(String.format(Locale.ROOT, "%.1f%%  ", pct(signedErrors[i], compared)));
            }
            out.add(signedLine.toString());
            out.add(String.format(Locale.ROOT, "Land above y 66 (%d columns): vanilla's own noise height (getBaseHeight) "
                    + "equals the real top on %.1f%%, ours on %.1f%%", landColumns, pct(baseIsReal, landColumns),
                    pct(baseIsOurs, landColumns)));
            heightExamples.forEach(line -> out.add("    " + line));
            String[] names = {"real y < 60", "60-66", "67-90", "> 90"};
            for (int b = 0; b < bands.length; b++) {
                int total = bands[b][0] + bands[b][1] + bands[b][2];
                out.add(String.format(Locale.ROOT, "  %-12s %6d columns: exact %.1f%%, ours higher %.1f%%, ours lower %.1f%%",
                        names[b], total, pct(bands[b][0], total), pct(bands[b][1], total), pct(bands[b][2], total)));
            }
            out.add(String.format(Locale.ROOT, "Trees: both %d, only real %d, only ours %d; plants: both %d, only real %d, "
                    + "only ours %d", bothTrees, realTreeOnly, oursTreeOnly, plantsBoth, plantsRealOnly, plantsOursOnly));
            int white = 0;
            int whiteMatch = 0;
            for (int[] snow : snowByBiome.values()) {
                white += snow[1] + snow[2] + snow[3];
                whiteMatch += snow[1];
            }
            out.add(String.format(Locale.ROOT, "Looks snowy (snow layer or snow block): agree on %d of %d snowy columns "
                    + "(%.1f%%); snow layer itself: both %d, only real %d, only ours %d", whiteMatch, white,
                    pct(whiteMatch, white), snowLayerBoth, snowLayerRealOnly, snowLayerOursOnly));
            out.add("Top block kind (under any snow layer), real -> ours (columns):");
            kinds.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> out.add(String.format(Locale.ROOT, "  %-24s %6d  %5.1f%%", e.getKey(), e.getValue(),
                            pct(e.getValue(), compared))));
            out.add("Most common block mismatches, real -> ours:");
            blocks.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(25)
                    .forEach(e -> out.add(String.format(Locale.ROOT, "  %-50s %6d", e.getKey(), e.getValue())));
            out.add("Looks snowy, by real biome: columns, both, only real, only ours:");
            snowByBiome.forEach((biome, s) -> {
                if (s[1] + s[2] + s[3] > 0) {
                    out.add(String.format(Locale.ROOT, "  %-36s %6d %6d %6d %6d", biome, s[0], s[1], s[2], s[3]));
                }
            });
            out.add("Examples of mismatches (x y z):");
            examples.forEach((pair, list) -> {
                out.add("  " + pair);
                list.forEach(line -> out.add("    " + line));
            });
        }

        private static double pct(int value, int total) {
            return total == 0 ? 0 : 100.0 * value / total;
        }

        private static String id(Block block) {
            return block.builtInRegistryHolder().key().location().getPath();
        }

        private static String name(Holder<Biome> biome) {
            return biome.unwrapKey().map(key -> key.location().getPath()).orElse("?");
        }
    }
}
