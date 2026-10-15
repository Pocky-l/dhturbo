package com.pockyl.dhturbo.compare;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import com.pockyl.dhturbo.DhTurbo;
import com.pockyl.dhturbo.surface.ColumnBuilder;
import com.pockyl.dhturbo.surface.RealStructures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
            List<String> report;
            int horizon = Integer.getInteger(DhTurbo.MOD_ID + ".compare.horizon", 0);
            if (horizon > 0) {
                // Speed only: the horizon around fresh spawns, as Distant Horizons requests it.
                report = HorizonBenchmark.run(server.overworld(), horizon, 3);
            } else {
                report = new ArrayList<>(compare(server.overworld(), areas));
                for (ServerLevel other : server.getAllLevels()) {
                    if (other != server.overworld()) {
                        report.addAll(compareProfiles(other, Math.max(4, areas / 4)));
                    }
                }
            }
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
                level.getSeed(), UnaryOperator.identity(), level.getStructureManager());
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
                    sink.add(x, z, real, ours, realBiome, biome);
                });
            }
        }
        List<String> report = new ArrayList<>();
        // Build cost alone (no comparison), one tile of 64 columns per spacing at every area's corner.
        StringBuilder timing = new StringBuilder("Build time per 64x64 tile:");
        for (int spacing : spacings) {
            Random timingRandom = new Random(level.getSeed());
            long nanos = 0;
            for (int area = 0; area < areas; area++) {
                int tileX = (timingRandom.nextInt(2 * RANGE) - RANGE) & ~63;
                int tileZ = (timingRandom.nextInt(2 * RANGE) - RANGE) & ~63;
                long begin = System.nanoTime();
                builder.build(tileX * spacing, tileZ * spacing, spacing, 64, (i, j, biome, layers) -> {
                });
                nanos += System.nanoTime() - begin;
            }
            timing.append(String.format(Locale.ROOT, " spacing %d %.1f ms", spacing, nanos / 1e6 / Math.max(1, areas)));
        }
        report.add(timing.toString());
        if (builder.vanillaSurface() != null) {
            long begin = System.nanoTime();
            for (int n = 0; n < 100; n++) {
                builder.vanillaSurface().pass(n * 64, 0, (x, z) -> 64, true);
            }
            report.add(String.format(Locale.ROOT, "Surface rule pass setup: %.2f ms", (System.nanoTime() - begin) / 1e6 / 100));
        }
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
            // The four cell corners around the column: our interpolated value with caves next to vanilla's.
            net.minecraft.world.level.levelgen.DensityFunction vanillaInner = com.pockyl.dhturbo.surface.SurfaceSampler
                    .columnCached(com.pockyl.dhturbo.surface.SurfaceSampler.interpolated(vanilla).getFirst());
            int cx = x & ~3;
            int cz = z & ~3;
            StringBuilder corners = new StringBuilder("  corners (ours with caves / vanilla interpolated):");
            for (int[] c : new int[][] {{cx, cz}, {cx + 4, cz}, {cx, cz + 4}, {cx + 4, cz + 4}}) {
                corners.append(" [").append(c[0]).append(',').append(c[1]).append(']');
                for (int y = Math.floorDiv(height - 12 - minY, cell) * cell + minY; y <= height + 8; y += cell) {
                    var point = new net.minecraft.world.level.levelgen.DensityFunction.SinglePointContext(c[0], y, c[1]);
                    corners.append(String.format(Locale.ROOT, " y%d %.4f/%.4f", y, builder.sampler().realDensity(c[0], y, c[1]),
                            vanillaInner.compute(point)));
                }
            }
            com.pockyl.dhturbo.surface.SurfaceTile tile = com.pockyl.dhturbo.surface.SurfaceTile.generate(builder.sampler(),
                    cx - 8, cz - 8, 1, 21, 1);
            corners.append(" | tile height ").append(tile.heightAt(x, z)).append(", corner heights");
            for (int[] c : new int[][] {{cx, cz}, {cx + 4, cz}, {cx, cz + 4}, {cx + 4, cz + 4}}) {
                corners.append(' ').append(tile.heightAt(c[0], c[1]));
            }
            stats[0].probes.add(corners.toString());
        }
        report.add(String.format(Locale.ROOT, "Seed %d, %d areas of %dx%d chunks, %.1f s", level.getSeed(), areas,
                AREA_CHUNKS, AREA_CHUNKS, (System.nanoTime() - start) / 1e9));
        for (int s = 0; s < spacings.length; s++) {
            report.add("");
            report.add(String.format(Locale.ROOT, "===== Columns %d block(s) wide: %d columns =====", spacings[s],
                    stats[s].columns));
            stats[s].report(report);
        }
        report.addAll(compareStructures(level, builder));
        return report;
    }

    /**
     * Structures: ours near the origin against the real world, which generates the chunks under them. Starts must be
     * the same (structure and bounding box), and the top of every column the structure built must match.
     */
    private static List<String> compareStructures(ServerLevel level, ColumnBuilder builder) {
        RealStructures structures = builder.realStructures();
        if (structures == null) {
            return List.of("Structures: off or none in this world");
        }
        int range = Integer.getInteger(DhTurbo.MOD_ID + ".compare.structureRange", 2500);
        int limit = Integer.getInteger(DhTurbo.MOD_ID + ".compare.structures", 12);
        long begin = System.nanoTime();
        List<RealStructures.Built> found = new ArrayList<>(structures.around(-range, -range, range, range));
        long ourMillis = (System.nanoTime() - begin) / 1_000_000;
        // Every kind once, nearest first, then more of them up to the limit.
        found.sort(Comparator.comparingLong(built -> Math.abs((long) built.startChunk().x)
                + Math.abs((long) built.startChunk().z)));
        List<RealStructures.Built> picked = new ArrayList<>();
        Set<String> kinds = new HashSet<>();
        for (RealStructures.Built built : found) {
            if (picked.size() < limit && kinds.add(built.name())) {
                picked.add(built);
            }
        }
        for (RealStructures.Built built : found) {
            if (picked.size() < limit && !picked.contains(built)) {
                picked.add(built);
            }
        }
        Registry<Structure> registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        Set<String> ourStarts = new HashSet<>();
        for (RealStructures.Built built : found) {
            ourStarts.add(built.name() + " " + built.startChunk());
        }
        boolean profiled = ColumnBuilder.profiled(level.dimensionType());
        // The coarse builds wide tiles use, from a second builder with empty caches.
        RealStructures coarse = profiled ? null : new ColumnBuilder(
                (NoiseBasedChunkGenerator) level.getChunkSource().getGenerator(), level.registryAccess(),
                level.dimensionType(), level.getSeed(), UnaryOperator.identity(), level.getStructureManager())
                .realStructures();
        long coarseColumns = 0;
        long coarseNear = 0;
        long coarseBlock = 0;
        long fineNear = 0;
        List<String> details = new ArrayList<>();
        Set<String> missing = new HashSet<>();
        int sameStart = 0;
        int sameBox = 0;
        long columns = 0;
        long exactTop = 0;
        long nearTop = 0;
        long sameBlock = 0;
        for (RealStructures.Built built : picked) {
            BoundingBox box = built.box();
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                    ChunkAccess chunk = level.getChunk(cx, cz);
                    for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
                        StructureStart start = entry.getValue();
                        String name = String.valueOf(registry.getKey(entry.getKey()));
                        if (start.isValid() && (ColumnBuilder.profiled(level.dimensionType())
                                || entry.getKey().step() == GenerationStep.Decoration.SURFACE_STRUCTURES)
                                && Math.abs(start.getChunkPos().x << 4) <= range
                                && Math.abs(start.getChunkPos().z << 4) <= range
                                && !ourStarts.contains(name + " " + start.getChunkPos())) {
                            missing.add(name + " " + start.getChunkPos());
                        }
                    }
                }
            }
            Structure structure = registry.get(ResourceLocation.parse(built.name()));
            StructureStart real = structure == null ? null
                    : level.getChunk(built.startChunk().x, built.startChunk().z).getStartForStructure(structure);
            boolean startMatches = real != null && real.isValid();
            // Pieces settle on the ground while they are placed, so only the horizontal extent is compared.
            boolean boxMatches = startMatches && real.getBoundingBox().minX() == box.minX()
                    && real.getBoundingBox().maxX() == box.maxX() && real.getBoundingBox().minZ() == box.minZ()
                    && real.getBoundingBox().maxZ() == box.maxZ();
            sameStart += startMatches ? 1 : 0;
            sameBox += boxMatches ? 1 : 0;
            long structureColumns = 0;
            long structureExact = 0;
            long structureBlock = 0;
            List<String> examples = new ArrayList<>();
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    RealStructures.Column column = built.columnAt(x, z);
                    if (column == null || column.top() == Integer.MIN_VALUE) {
                        continue;
                    }
                    if (profiled) {
                        // No single top here (the Nether's roof is above everything): every block the structure placed.
                        ChunkAccess chunk = level.getChunk(x >> 4, z >> 4);
                        for (int k = 0; k < column.states().length; k++) {
                            BlockState state = column.states()[k];
                            if (state.isAir()) {
                                continue;
                            }
                            for (int y = column.bottoms()[k]; y < column.tops()[k]; y++) {
                                BlockState realBlock = chunk.getBlockState(new BlockPos(x, y, z));
                                structureColumns++;
                                if (realBlock.getBlock() == state.getBlock()) {
                                    structureExact++;
                                    structureBlock++;
                                    nearTop++;
                                } else if (examples.size() < 3) {
                                    examples.add(x + " " + y + " " + z + ": ours "
                                            + BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath() + ", real "
                                            + BuiltInRegistries.BLOCK.getKey(realBlock.getBlock()).getPath());
                                }
                            }
                        }
                        continue;
                    }
                    // The top block that is not air, a fluid or a snow layer, on both sides (underwater ruins).
                    int ourTop = Integer.MIN_VALUE;
                    BlockState ourBlock = null;
                    for (int k = column.states().length - 1; k >= 0 && ourBlock == null; k--) {
                        BlockState state = column.states()[k];
                        if (!state.isAir() && !state.liquid() && !state.is(Blocks.SNOW)) {
                            ourBlock = state;
                            ourTop = column.tops()[k];
                        }
                    }
                    if (ourBlock == null) {
                        continue;
                    }
                    ChunkAccess chunk = level.getChunk(x >> 4, z >> 4);
                    int realTop = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) + 1;
                    BlockState realBlock = chunk.getBlockState(new BlockPos(x, realTop - 1, z));
                    while (realTop > level.getMinBuildHeight()
                            && (realBlock.isAir() || realBlock.liquid() || realBlock.is(Blocks.SNOW))) {
                        realTop--;
                        realBlock = chunk.getBlockState(new BlockPos(x, realTop - 1, z));
                    }
                    structureColumns++;
                    if (realTop == ourTop) {
                        structureExact++;
                    }
                    if (Math.abs(realTop - ourTop) <= 1) {
                        nearTop++;
                    }
                    if (ourBlock != null && ourBlock.getBlock() == realBlock.getBlock()) {
                        structureBlock++;
                    } else if (examples.size() < 3) {
                        examples.add(x + " " + z + ": ours " + BuiltInRegistries.BLOCK.getKey(ourBlock.getBlock()).getPath()
                                + " top " + ourTop + ", real " + BuiltInRegistries.BLOCK.getKey(realBlock.getBlock()).getPath()
                                + " top " + realTop);
                    }
                }
            }
            columns += structureColumns;
            exactTop += structureExact;
            sameBlock += structureBlock;
            long roughColumns = coarseColumns;
            long roughNear = coarseNear;
            long roughFine = fineNear;
            List<String> roughExamples = new ArrayList<>();
            if (coarse != null) {
                // Columns 4 blocks apart, as a spacing-4 tile samples them: our top there against the real one.
                RealStructures.Built rough = null;
                for (RealStructures.Built candidate : coarse.around(box.minX(), box.minZ(), box.maxX(), box.maxZ(), false)) {
                    if (candidate.startChunk().equals(built.startChunk()) && candidate.name().equals(built.name())) {
                        rough = candidate;
                    }
                }
                for (int x = Math.floorDiv(box.minX() + 3, 4) * 4; rough != null && x <= box.maxX(); x += 4) {
                    for (int z = Math.floorDiv(box.minZ() + 3, 4) * 4; z <= box.maxZ(); z += 4) {
                        RealStructures.Column column = rough.columnAt(x, z);
                        if (column == null || column.top() == Integer.MIN_VALUE) {
                            continue;
                        }
                        int ourTop = Integer.MIN_VALUE;
                        BlockState ourBlock = null;
                        for (int k = column.states().length - 1; k >= 0 && ourBlock == null; k--) {
                            BlockState state = column.states()[k];
                            if (!state.isAir() && !state.liquid() && !state.is(Blocks.SNOW)) {
                                ourBlock = state;
                                ourTop = column.tops()[k];
                            }
                        }
                        if (ourBlock == null) {
                            continue;
                        }
                        ChunkAccess chunk = level.getChunk(x >> 4, z >> 4);
                        int realTop = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) + 1;
                        BlockState realBlock = chunk.getBlockState(new BlockPos(x, realTop - 1, z));
                        while (realTop > level.getMinBuildHeight()
                                && (realBlock.isAir() || realBlock.liquid() || realBlock.is(Blocks.SNOW))) {
                            realTop--;
                            realBlock = chunk.getBlockState(new BlockPos(x, realTop - 1, z));
                        }
                        coarseColumns++;
                        RealStructures.Column exact = built.columnAt(x, z);
                        if (exact != null && Math.abs(realTop - topOf(exact)) <= 1) {
                            fineNear++;
                        }
                        if (Math.abs(realTop - ourTop) <= 1) {
                            coarseNear++;
                        } else if (roughExamples.size() < 3) {
                            roughExamples.add(x + " " + z + ": ours " + BuiltInRegistries.BLOCK.getKey(ourBlock.getBlock()).getPath()
                                    + " top " + ourTop + ", real " + BuiltInRegistries.BLOCK.getKey(realBlock.getBlock()).getPath()
                                    + " top " + realTop);
                        }
                        if (ourBlock.getBlock() == realBlock.getBlock()) {
                            coarseBlock++;
                        }
                    }
                }
            }
            String boxText = boxMatches ? "same (y " + real.getBoundingBox().minY() + "-" + real.getBoundingBox().maxY()
                    + " real, " + box.minY() + "-" + box.maxY() + " ours)" : real != null && real.isValid()
                    ? "differs (real " + real.getBoundingBox() + ", ours " + box + ")" : "-";
            details.add(String.format(Locale.ROOT,
                    "  %s at %d %d: real start %s, box %s, top exact %.1f%%, top block %.1f%% of %d columns%s",
                    built.name(), box.getCenter().getX(), box.getCenter().getZ(), startMatches ? "yes" : "NO", boxText,
                    percent(structureExact, structureColumns), percent(structureBlock, structureColumns),
                    structureColumns, built.adaptedGround() ? ", ground adapted" : "")
                    + (examples.isEmpty() ? "" : "; e.g. " + String.join("; ", examples))
                    + String.format(Locale.ROOT, " | coarse within 1 %.1f%% (exact build %.1f%%) of %d",
                    percent(coarseNear - roughNear, coarseColumns - roughColumns),
                    percent(fineNear - roughFine, coarseColumns - roughColumns), coarseColumns - roughColumns)
                    + (roughExamples.isEmpty() ? "" : "; e.g. " + String.join("; ", roughExamples)));
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "Structures: %d within %d blocks of the origin (%d kinds, %d ms), %d compared",
                found.size(), range, kinds.size(), ourMillis, picked.size()));
        lines.add(String.format(Locale.ROOT,
                profiled ? "  starts real %d/%d, same area %d/%d; %d placed blocks: same block %.1f%% (%.1f%%, %.1f%%)"
                : "  starts real %d/%d, same area %d/%d; %d columns: top exact %.1f%%, within 1 %.1f%%, same top block %.1f%%",
                sameStart, picked.size(), sameBox, picked.size(), columns, percent(exactTop, columns),
                percent(nearTop, columns), percent(sameBlock, columns)));
        if (coarse != null) {
            lines.add(String.format(Locale.ROOT,
                    "  coarse builds (wide tiles), every 4th column: %d columns, top within 1 %.1f%% (exact build there %.1f%%), same top block %.1f%%",
                    coarseColumns, percent(coarseNear, coarseColumns), percent(fineNear, coarseColumns),
                    percent(coarseBlock, coarseColumns)));
        }
        lines.add("  real structures near them that we lack: " + (missing.isEmpty() ? "none" : missing));
        lines.addAll(details);
        return lines;
    }

    /**
     * Worlds without a single surface (the Nether, the End): every block of every column against real chunks, plus the
     * blocks on top of floors (the first solid block under an open one) and the structures near the origin.
     */
    private static List<String> compareProfiles(ServerLevel level, int areas) {
        if (!(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator)
                || !ColumnBuilder.profiled(level.dimensionType())) {
            return List.of();
        }
        ColumnBuilder builder = new ColumnBuilder(generator, level.registryAccess(), level.dimensionType(),
                level.getSeed(), UnaryOperator.identity(), level.getStructureManager());
        int minY = level.getMinBuildHeight();
        int height = level.getHeight();
        Random random = new Random(level.getSeed() ^ 0xBEEF);
        long blocks = 0;
        long sameSolid = 0;
        long floors = 0;
        long sameFloor = 0;
        long sameFloorKind = 0;
        Map<String, Integer> confusion = new TreeMap<>();
        long begin = System.nanoTime();
        int range = level.dimensionType().hasCeiling() ? RANGE / 8 : RANGE / 4;
        for (int area = 0; area < areas; area++) {
            int chunkX = (random.nextInt(2 * range) - range) >> 4;
            int chunkZ = (random.nextInt(2 * range) - range) >> 4;
            ChunkAccess[] chunks = new ChunkAccess[AREA_CHUNKS * AREA_CHUNKS];
            for (int dx = 0; dx < AREA_CHUNKS; dx++) {
                for (int dz = 0; dz < AREA_CHUNKS; dz++) {
                    chunks[dx + dz * AREA_CHUNKS] = level.getChunk(chunkX + dx, chunkZ + dz);
                }
            }
            int minX = chunkX << 4;
            int minZ = chunkZ << 4;
            long[] totals = new long[5];
            builder.build(minX, minZ, 1, AREA_CHUNKS * 16, (i, j, biome, layers) -> {
                int x = minX + i;
                int z = minZ + j;
                ChunkAccess chunk = chunks[(i >> 4) + (j >> 4) * AREA_CHUNKS];
                BlockState[] ours = new BlockState[height];
                for (ColumnBuilder.Layer layer : layers) {
                    for (int y = layer.bottom(); y < layer.top() && y < height; y++) {
                        ours[y] = layer.state();
                    }
                }
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                for (int y = 0; y < height; y++) {
                    BlockState real = chunk.getBlockState(pos.set(x, minY + y, z));
                    // Plants, roots and fire do not count: features put them there, not the terrain.
                    boolean realSolid = real.blocksMotion();
                    boolean ourSolid = ours[y] != null && ours[y].blocksMotion();
                    totals[0]++;
                    if (realSolid == ourSolid) {
                        totals[1]++;
                    }
                    // Floors: a solid block with an open one above it in the real world.
                    if (realSolid && y + 1 < height) {
                        BlockState above = chunk.getBlockState(pos.set(x, minY + y + 1, z));
                        if (!above.blocksMotion()) {
                            totals[2]++;
                            if (ourSolid) {
                                totals[3]++;
                                if (ours[y].getBlock() == real.getBlock()) {
                                    totals[4]++;
                                } else {
                                    synchronized (confusion) {
                                        confusion.merge(BuiltInRegistries.BLOCK.getKey(real.getBlock()).getPath() + " -> "
                                                + BuiltInRegistries.BLOCK.getKey(ours[y].getBlock()).getPath(), 1, Integer::sum);
                                    }
                                }
                            }
                        }
                    }
                }
            });
            blocks += totals[0];
            sameSolid += totals[1];
            floors += totals[2];
            sameFloor += totals[3];
            sameFloorKind += totals[4];
        }
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add(String.format(Locale.ROOT, "===== %s: %d areas of 2x2 chunks, %.1f s =====", level.dimension().location(),
                areas, (System.nanoTime() - begin) / 1e9));
        lines.add(String.format(Locale.ROOT,
                "Blocks solid/open as in the real world: %.1f%% of %d; floors solid on our side %.1f%%, same block %.1f%% of %d",
                percent(sameSolid, blocks), blocks, percent(sameFloor, floors), percent(sameFloorKind, sameFloor), floors));
        confusion.entrySet().stream().sorted((left, right) -> right.getValue() - left.getValue()).limit(8)
                .forEach(entry -> lines.add("  floor " + entry.getKey() + ": " + entry.getValue()));
        lines.addAll(compareStructures(level, builder));
        return lines;
    }

    /** Top of the highest block of a structure column that is not air, a fluid or a snow layer. */
    private static int topOf(RealStructures.Column column) {
        for (int k = column.states().length - 1; k >= 0; k--) {
            BlockState state = column.states()[k];
            if (!state.isAir() && !state.liquid() && !state.is(Blocks.SNOW)) {
                return column.tops()[k];
            }
        }
        return Integer.MIN_VALUE;
    }

    private static double percent(long part, long whole) {
        return whole == 0 ? 0 : 100.0 * part / whole;
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
            if (!state.blocksMotion() && !state.liquid() && !state.is(Blocks.POWDER_SNOW)) {
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
        int landExact;
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

        void add(int x, int z, Surface real, Surface ours, Holder<Biome> realBiome, Holder<Biome> ourBiome) {
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
            // Plants only where no tree hides the ground (crowns cover them from above anyway).
            if (real.tree || ours.tree) {
                // skip
            } else if (real.plant && ours.plant) {
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
                if (ours.height == real.height) {
                    landExact++;
                } else if (heightExamples.size() < 12) {
                    heightExamples.add(String.format(Locale.ROOT, "%d %d: real %d, ours %d", x, z, real.height, ours.height));
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
            // Vanilla's own noise height (getBaseHeight, no carvers or features) matched the real top on 97.8% here
            // (2026-10-12); it is printed for probes only, computing it for every column tripled the run time.
            out.add(String.format(Locale.ROOT, "Land above y 66 (%d columns): ours equals the real top on %.1f%%",
                    landColumns, pct(landExact, landColumns)));
            heightExamples.forEach(line -> out.add("    " + line));
            String[] names = {"real y < 60", "60-66", "67-90", "> 90"};
            for (int b = 0; b < bands.length; b++) {
                int total = bands[b][0] + bands[b][1] + bands[b][2];
                out.add(String.format(Locale.ROOT, "  %-12s %6d columns: exact %.1f%%, ours higher %.1f%%, ours lower %.1f%%",
                        names[b], total, pct(bands[b][0], total), pct(bands[b][1], total), pct(bands[b][2], total)));
            }
            out.add(String.format(Locale.ROOT, "Trees: both %d, only real %d, only ours %d; plants (no tree): both %d, only real %d, "
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
