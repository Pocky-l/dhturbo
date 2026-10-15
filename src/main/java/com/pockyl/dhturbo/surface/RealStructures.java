package com.pockyl.dhturbo.surface;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraftforge.common.world.PieceBeardifierModifier;

import com.pockyl.dhturbo.DhTurbo;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.LongAdder;

/**
 * Structures (villages, temples, outposts, shipwrecks, mansions, ...) exactly where and as the game will build them,
 * without generating chunks.
 * <p>
 * Positions come from the game's own structure placement: the same structure sets, the same seeds and the same
 * {@link Structure#generate} call chunk generation makes, so a structure exists here exactly when it exists in the
 * world. Its pieces are then built on a {@link VirtualLevel} standing on the terrain, with the random every chunk gives
 * it in the {@code SURFACE_STRUCTURES} step, and the blocks they place are kept per column. Structures that adapt the
 * terrain (villages flatten the ground under their houses) get vanilla's {@link Beardifier} added to the density, so
 * the ground around them is the real one too.
 * <p>
 * Only structures of the surface step are built; mineshafts, strongholds, ancient cities and other underground ones
 * cannot be seen from afar. Built structures are cached: neighbouring tiles and every detail level reuse them.
 */
public final class RealStructures {
    /** Widest columns that show structures: beyond that a village is a handful of columns. */
    public static final int MAX_SPACING = 16;
    /**
     * Widest columns that get structures built block-exactly (terrain sampled per block, vanilla's terrain adaptation).
     * Wider columns get the coarse build: terrain sampled every few blocks and the ground under each piece set to its
     * floor, which is what those columns can show.
     */
    public static final int FINE_MAX_SPACING = 2;
    /**
     * Terrain sample spacing of coarse builds: the lattice wide tiles sample by default (two cells); structures up to
     * {@link #SMALL_SIZE} blocks across, which place themselves on the averaged ground (shipwrecks, ruins, huts), get
     * every cell corner with vanilla's heights there.
     */
    private static final int COARSE_STEP = 2 * SurfaceTile.CELL_WIDTH;
    private static final int SMALL_SIZE = 64;
    /** Structures bigger than this (blocks across) are skipped: building them would stall a tile. */
    private static final int MAX_SIZE = 400;
    /** Vanilla only lets pieces reach this many chunks from the structure's start chunk (jigsaw limit 128 blocks). */
    private static final int REACH_CHUNKS = 8;
    private static final int STEP = GenerationStep.Decoration.SURFACE_STRUCTURES.ordinal();
    /**
     * Weight of built structures kept: filled columns (a village has 2000-4000, about 120 bytes each) plus their box
     * (4 bytes a column), about 60 MB at most.
     */
    private static final int CACHED_COLUMNS = 500_000;
    private static final int CACHED_CHUNKS = 16384;
    /** A structure whose top is this far under the ground at its center is buried and left out. */
    private static final int UNDERGROUND_MARGIN = 8;

    private final SampledHeightGenerator generator;
    private final SurfaceSampler sampler;
    /** Full column profiles in worlds without a single surface (the Nether, the End); null for height fields. */
    @Nullable
    private final ProfileTerrain profile;
    private final StructureTemplateManager templates;
    private final RegistryAccess registries;
    private final DimensionType dimensionType;
    private final RandomState randomState;
    private final ChunkGeneratorStructureState state;
    private final LevelHeightAccessor heightAccessor;
    private final long seed;
    private final int minY;
    private final int height;
    private final int seaLevel;
    /** Structure sets that can place something here; concentric rings (strongholds) are underground only. */
    private final List<Holder<StructureSet>> sets;
    /** Index of each structure among the structures of its generation step: part of its placement seed. */
    private final Object2IntMap<Structure> stepIndex = new Object2IntOpenHashMap<>();
    /** Starts per chunk (only chunks some set places a structure in). */
    private final Cache<Long, List<StructureStart>> starts = CacheBuilder.newBuilder().maximumSize(CACHED_CHUNKS).build();
    private final Cache<StructureStart, Built> placed = CacheBuilder.newBuilder().maximumWeight(CACHED_COLUMNS)
            .<StructureStart, Built>weigher((start, built) -> built.weight()).weakKeys().build();
    /** Coarse builds for wide columns (see {@link #FINE_MAX_SPACING}). */
    private final Cache<StructureStart, Built> placedCoarse = CacheBuilder.newBuilder().maximumWeight(CACHED_COLUMNS)
            .<StructureStart, Built>weigher((start, built) -> built.weight()).weakKeys().build();
    private final LongAdder failures = new LongAdder();
    /** Nanoseconds spent per phase: finding starts, terrain, terrain adaptation, pieces. */
    private final LongAdder[] timings = {new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder()};
    private static final Map<String, Boolean> REPORTED = new ConcurrentHashMap<>();

    public RealStructures(NoiseBasedChunkGenerator generator, SurfaceSampler sampler, StructureTemplateManager templates,
                          long seed, RegistryAccess registries, DimensionType dimensionType) {
        // Structure placement asks for terrain heights all the time: answer from the sampler, not the full column.
        this.profile = ColumnBuilder.profiled(dimensionType) ? new ProfileTerrain(sampler) : null;
        this.generator = new SampledHeightGenerator(generator, sampler, profile);
        this.sampler = sampler;
        this.templates = templates;
        this.registries = registries;
        this.dimensionType = dimensionType;
        this.seed = seed;
        // The level's own height: structures may rise above the noise (End cities reach far above its terrain).
        this.minY = dimensionType.minY();
        this.height = dimensionType.height();
        this.seaLevel = sampler.seaLevel();
        this.heightAccessor = LevelHeightAccessor.create(minY, height);
        // The real noises (with caves): structures probe heights the way chunk generation does.
        this.randomState = RandomState.create(generator.generatorSettings().value(),
                registries.lookupOrThrow(Registries.NOISE), seed);
        this.state = ChunkGeneratorStructureState.createForNormal(randomState, seed, generator.getBiomeSource(),
                registries.lookupOrThrow(Registries.STRUCTURE_SET));
        this.sets = state.possibleStructureSets().stream()
                .filter(set -> !(set.value().placement() instanceof ConcentricRingsStructurePlacement))
                .filter(set -> set.value().structures().stream().anyMatch(entry -> visible(entry.structure().value())))
                .toList();
        // Same grouping as ChunkGenerator.applyBiomeDecoration: registry order within each step.
        Map<Integer, Integer> counts = new java.util.HashMap<>();
        registries.registryOrThrow(Registries.STRUCTURE).stream().forEach(structure -> {
            int step = structure.step().ordinal();
            int index = counts.merge(step, 1, Integer::sum) - 1;
            stepIndex.put(structure, index);
        });
    }

    /**
     * Structures this generator builds: the ones on the surface; in worlds without one (the Nether's fortresses stand
     * in its caverns) all of them.
     */
    private boolean visible(Structure structure) {
        return profile != null || structure.step().ordinal() == STEP;
    }

    public boolean isEmpty() {
        return sets.isEmpty();
    }

    /** Pieces that failed on the virtual level since the last call (they called something it cannot do). */
    public long takeFailures() {
        return failures.sumThenReset();
    }

    /** Milliseconds spent so far finding starts, sampling terrain, adapting it and placing pieces. */
    public String timings() {
        return String.format("starts %d ms, terrain %d ms, adaptation %d ms, pieces %d ms",
                timings[0].sum() / 1_000_000, timings[1].sum() / 1_000_000, timings[2].sum() / 1_000_000,
                timings[3].sum() / 1_000_000);
    }

    /** Structures reaching into a block area (inclusive bounds), built block-exactly and cached. */
    public List<Built> around(int minX, int minZ, int maxX, int maxZ) {
        return around(minX, minZ, maxX, maxZ, true);
    }

    /**
     * Structures reaching into a block area (inclusive bounds), built and cached; {@code fine} for columns up to
     * {@link #FINE_MAX_SPACING} wide, otherwise a coarse build is enough (or the fine one when it already exists).
     */
    public List<Built> around(int minX, int minZ, int maxX, int maxZ, boolean fine) {
        List<Built> result = new ArrayList<>();
        int fromChunkX = (minX >> 4) - REACH_CHUNKS;
        int fromChunkZ = (minZ >> 4) - REACH_CHUNKS;
        int toChunkX = (maxX >> 4) + REACH_CHUNKS;
        int toChunkZ = (maxZ >> 4) + REACH_CHUNKS;
        for (long chunk : candidateChunks(fromChunkX, fromChunkZ, toChunkX, toChunkZ)) {
            for (StructureStart start : startsAt(ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) {
                Built built = built(start, fine);
                if (built != null && built.intersects(minX, minZ, maxX, maxZ)) {
                    result.add(built);
                }
            }
        }
        return result;
    }

    /**
     * Chunks in the range some structure set may start a structure in. Random spread sets (nearly all) place at most
     * one structure per region of {@code spacing} chunks, so only that region's chosen chunk is checked.
     */
    private List<Long> candidateChunks(int fromX, int fromZ, int toX, int toZ) {
        List<Long> chunks = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Holder<StructureSet> set : sets) {
            StructurePlacement placement = set.value().placement();
            if (placement instanceof RandomSpreadStructurePlacement spread) {
                int spacing = spread.spacing();
                for (int regionX = Math.floorDiv(fromX, spacing); regionX <= Math.floorDiv(toX, spacing); regionX++) {
                    for (int regionZ = Math.floorDiv(fromZ, spacing); regionZ <= Math.floorDiv(toZ, spacing); regionZ++) {
                        ChunkPos chunk = spread.getPotentialStructureChunk(seed, regionX * spacing, regionZ * spacing);
                        if (chunk.x >= fromX && chunk.x <= toX && chunk.z >= fromZ && chunk.z <= toZ
                                && seen.add(chunk.toLong())) {
                            chunks.add(chunk.toLong());
                        }
                    }
                }
            } else {
                // Unknown placement types (mods): every chunk.
                for (int x = fromX; x <= toX; x++) {
                    for (int z = fromZ; z <= toZ; z++) {
                        if (seen.add(ChunkPos.asLong(x, z))) {
                            chunks.add(ChunkPos.asLong(x, z));
                        }
                    }
                }
            }
        }
        return chunks;
    }

    /** The visible structures starting in a chunk, as {@code ChunkGenerator.createStructures} picks them. */
    List<StructureStart> startsAt(int chunkX, int chunkZ) {
        try {
            return starts.get(ChunkPos.asLong(chunkX, chunkZ), () -> findStarts(chunkX, chunkZ));
        } catch (ExecutionException | RuntimeException e) {
            report("structure starts", e);
            return List.of();
        }
    }

    private List<StructureStart> findStarts(int chunkX, int chunkZ) {
        long begin = System.nanoTime();
        try {
            return findStartsTimed(chunkX, chunkZ);
        } finally {
            timings[0].add(System.nanoTime() - begin);
        }
    }

    private List<StructureStart> findStartsTimed(int chunkX, int chunkZ) {
        List<StructureStart> found = new ArrayList<>(1);
        ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
        for (Holder<StructureSet> set : sets) {
            if (!set.value().placement().isStructureChunk(state, chunkX, chunkZ)) {
                continue;
            }
            List<StructureSet.StructureSelectionEntry> entries = set.value().structures();
            StructureStart start = null;
            if (entries.size() == 1) {
                start = generate(entries.get(0), chunk);
            } else {
                // Weighted pick, retried without the failed entry: the same sequence as vanilla.
                List<StructureSet.StructureSelectionEntry> left = new ArrayList<>(entries);
                WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
                random.setLargeFeatureSeed(seed, chunkX, chunkZ);
                int total = 0;
                for (StructureSet.StructureSelectionEntry entry : left) {
                    total += entry.weight();
                }
                while (!left.isEmpty()) {
                    int pick = random.nextInt(total);
                    int index = 0;
                    for (StructureSet.StructureSelectionEntry entry : left) {
                        pick -= entry.weight();
                        if (pick < 0) {
                            break;
                        }
                        index++;
                    }
                    StructureSet.StructureSelectionEntry entry = left.get(index);
                    start = generate(entry, chunk);
                    if (start != null) {
                        break;
                    }
                    left.remove(index);
                    total -= entry.weight();
                }
            }
            if (start != null && visible(start.getStructure())) {
                found.add(start);
            }
        }
        return found.isEmpty() ? List.of() : found;
    }

    @Nullable
    private StructureStart generate(StructureSet.StructureSelectionEntry entry, ChunkPos chunk) {
        Structure structure = entry.structure().value();
        if (structure instanceof OceanMonumentStructure && !monumentSurroundingPossible(chunk)) {
            return null;
        }
        StructureStart start = structure.generate(registries, generator, generator.getBiomeSource(), randomState,
                templates, seed, chunk, 0, heightAccessor, structure.biomes()::contains);
        return start.isValid() ? start : null;
    }

    /**
     * A cheap part of the monument's own check: it wants every biome within 29 blocks of the chunk's (9, 9) at sea level
     * to be deep ocean, and samples ~3000 biomes for that, mostly on land. 27 of the same samples (the corners, edge
     * middles and centre of its lattice) reject nearly all chunks first; the rest go through the full check.
     */
    private boolean monumentSurroundingPossible(ChunkPos chunk) {
        int x = chunk.getBlockX(9);
        int y = generator.getSeaLevel();
        int z = chunk.getBlockZ(9);
        int radius = 29;
        int fromX = QuartPos.fromBlock(x - radius);
        int fromY = QuartPos.fromBlock(y - radius);
        int fromZ = QuartPos.fromBlock(z - radius);
        int toX = QuartPos.fromBlock(x + radius);
        int toY = QuartPos.fromBlock(y + radius);
        int toZ = QuartPos.fromBlock(z + radius);
        int stepX = Math.max(1, (toX - fromX) / 2);
        int stepY = Math.max(1, (toY - fromY) / 2);
        int stepZ = Math.max(1, (toZ - fromZ) / 2);
        for (int qx = fromX; qx <= toX; qx += stepX) {
            for (int qz = fromZ; qz <= toZ; qz += stepZ) {
                for (int qy = fromY; qy <= toY; qy += stepY) {
                    if (!generator.getBiomeSource().getNoiseBiome(qx, qy, qz, randomState.sampler())
                            .is(BiomeTags.REQUIRED_OCEAN_MONUMENT_SURROUNDING)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    @Nullable
    private Built built(StructureStart start, boolean fine) {
        if (!shown(start)) {
            return null;
        }
        try {
            if (fine || profile != null) {
                return placed.get(start, () -> place(start));
            }
            Built exact = placed.getIfPresent(start);
            return exact != null ? exact : placedCoarse.get(start, () -> placeCoarse(start));
        } catch (ExecutionException | RuntimeException e) {
            report("structure " + name(start.getStructure()), e);
            return null;
        }
    }

    /** Whether a structure is drawn: not too big to build, and not buried. */
    private boolean shown(StructureStart start) {
        BoundingBox box = start.getBoundingBox();
        if (box.getXSpan() > MAX_SIZE || box.getZSpan() > MAX_SIZE) {
            return false;
        }
        if (profile == null) {
            // Some packs put buried structures in the surface step (Terralith's underground cabins): nothing to see.
            BlockPos center = box.getCenter();
            return box.maxY() >= generator.ground(center.getX(), center.getZ()) - UNDERGROUND_MARGIN;
        }
        return true;
    }

    /**
     * Whether everything {@link #around(int, int, int, int, boolean) around} needs for wide columns over the area is
     * already found and built, so a tile there does not wait for structures.
     */
    public boolean prepared(int minX, int minZ, int maxX, int maxZ) {
        for (long chunk : candidateChunks((minX >> 4) - REACH_CHUNKS, (minZ >> 4) - REACH_CHUNKS,
                (maxX >> 4) + REACH_CHUNKS, (maxZ >> 4) + REACH_CHUNKS)) {
            List<StructureStart> found = starts.getIfPresent(chunk);
            if (found == null) {
                return false;
            }
            for (StructureStart start : found) {
                if (shown(start) && placed.getIfPresent(start) == null && placedCoarse.getIfPresent(start) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A structure as built: its blocks per column and the ground it adapted. */
    public static final class Built {
        private final String name;
        private final BoundingBox box;
        private final ChunkPos startChunk;
        private final int minX;
        private final int minZ;
        private final int sizeX;
        private final int sizeZ;
        /** Per column of the structure's box: its blocks, or null. */
        private final Column[] columns;
        /**
         * Ground area (box plus the reach of terrain adaptation): its heights every {@code groundStep} blocks,
         * MIN_VALUE where unchanged.
         */
        private final int groundMinX;
        private final int groundMinZ;
        private final int groundSize;
        private final int groundStep;
        @Nullable
        private final int[] ground;

        Built(String name, BoundingBox box, ChunkPos startChunk, Column[] columns, int groundMinX, int groundMinZ,
              int groundSize, int groundStep, @Nullable int[] ground) {
            this.name = name;
            this.box = box;
            this.startChunk = startChunk;
            this.minX = box.minX();
            this.minZ = box.minZ();
            this.sizeX = box.getXSpan();
            this.sizeZ = box.getZSpan();
            this.columns = columns;
            this.groundMinX = groundMinX;
            this.groundMinZ = groundMinZ;
            this.groundSize = groundSize;
            this.groundStep = groundStep;
            this.ground = ground;
        }

        /** Cache weight: filled columns, and the box at 1/32 (an array slot against a column's runs). */
        int weight() {
            int filled = 0;
            for (Column column : columns) {
                if (column != null) {
                    filled++;
                }
            }
            return 1 + filled + columns.length / 32 + (ground != null ? ground.length / 32 : 0);
        }

        /** The structure's id, e.g. {@code minecraft:village_plains}. */
        public String name() {
            return name;
        }

        /** The structure's bounding box, as its start has it. */
        public BoundingBox box() {
            return box;
        }

        public ChunkPos startChunk() {
            return startChunk;
        }

        public int minX() {
            return minX;
        }

        public int minZ() {
            return minZ;
        }

        public int sizeX() {
            return sizeX;
        }

        public int sizeZ() {
            return sizeZ;
        }

        /** Whether the structure reshaped the terrain around it. */
        public boolean adaptedGround() {
            return ground != null;
        }

        boolean intersects(int fromX, int fromZ, int toX, int toZ) {
            int reachMinX = ground != null ? groundMinX : minX;
            int reachMinZ = ground != null ? groundMinZ : minZ;
            int reachMaxX = ground != null ? groundMinX + groundSize * groundStep - 1 : minX + sizeX - 1;
            int reachMaxZ = ground != null ? groundMinZ + groundSize * groundStep - 1 : minZ + sizeZ - 1;
            return reachMaxX >= fromX && reachMinX <= toX && reachMaxZ >= fromZ && reachMinZ <= toZ;
        }

        /**
         * Ground height the structure gave the column, or MIN_VALUE when it left it alone. Coarse builds know it on
         * vanilla's cell corners, where wide columns are.
         */
        public int groundAt(int x, int z) {
            if (ground == null) {
                return Integer.MIN_VALUE;
            }
            int i = Math.floorDiv(x - groundMinX, groundStep);
            int j = Math.floorDiv(z - groundMinZ, groundStep);
            return i < 0 || j < 0 || i >= groundSize || j >= groundSize ? Integer.MIN_VALUE : ground[i + j * groundSize];
        }

        @Nullable
        public Column columnAt(int x, int z) {
            int i = x - minX;
            int j = z - minZ;
            return i < 0 || j < 0 || i >= sizeX || j >= sizeZ ? null : columns[i + j * sizeX];
        }
    }

    /**
     * Blocks a structure placed in one column, bottom to top, as runs of one block state (absolute Y, top exclusive);
     * Y between runs was not touched. {@code top} is one above the highest non-air block, MIN_VALUE when all are air.
     */
    public record Column(int[] bottoms, int[] tops, BlockState[] states, int top) {
    }

    /** For a column {@code spacing} blocks wide at (x, z): the structure column with the highest top in it. */
    @Nullable
    public static Column columnFor(List<Built> structures, int x, int z, int spacing) {
        Column best = null;
        for (Built built : structures) {
            int fromX = Math.max(x, built.minX);
            int toX = Math.min(x + spacing - 1, built.minX + built.sizeX - 1);
            int fromZ = Math.max(z, built.minZ);
            int toZ = Math.min(z + spacing - 1, built.minZ + built.sizeZ - 1);
            for (int bx = fromX; bx <= toX; bx++) {
                for (int bz = fromZ; bz <= toZ; bz++) {
                    Column column = built.columnAt(bx, bz);
                    if (column != null && (best == null || column.top() > best.top())) {
                        best = column;
                    }
                }
            }
        }
        return best;
    }

    /** The ground the structures gave the column at (x, z), or MIN_VALUE. */
    public static int groundFor(List<Built> structures, int x, int z) {
        int ground = Integer.MIN_VALUE;
        for (Built built : structures) {
            int value = built.groundAt(x, z);
            if (value != Integer.MIN_VALUE) {
                ground = value;
            }
        }
        return ground;
    }

    /** Builds a structure: adapts the terrain, places its pieces chunk by chunk, keeps the blocks per column. */
    private Built place(StructureStart start) {
        if (profile != null) {
            return placeInProfile(start);
        }
        long begin = System.nanoTime();
        BoundingBox box = start.getBoundingBox();
        Structure structure = start.getStructure();
        boolean adapts = structure.terrainAdaptation() != TerrainAdjustment.NONE;
        int reach = (adapts ? Beardifier.BEARD_KERNEL_RADIUS : 0) + 1;
        // Terrain under and around the box on vanilla's cell lattice (exact heights).
        int groundMinX = Math.floorDiv(box.minX() - reach, SurfaceTile.CELL_WIDTH) * SurfaceTile.CELL_WIDTH;
        int groundMinZ = Math.floorDiv(box.minZ() - reach, SurfaceTile.CELL_WIDTH) * SurfaceTile.CELL_WIDTH;
        int groundSize = Math.max(box.maxX() + reach - groundMinX, box.maxZ() + reach - groundMinZ) + 1;
        SurfaceTile raw = SurfaceTile.generate(sampler, groundMinX, groundMinZ, 1, groundSize, 1);
        int[] ground = raw.heights.clone();
        long terrainDone = System.nanoTime();
        timings[1].add(terrainDone - begin);
        boolean changed = adapts && adaptTerrain(start, groundMinX, groundMinZ, groundSize, ground);
        long adaptDone = System.nanoTime();
        timings[2].add(adaptDone - terrainDone);
        int[] adapted = null;
        if (changed) {
            // Only the changed columns count as the structure's ground.
            adapted = ground.clone();
            for (int i = 0; i < adapted.length; i++) {
                if (adapted[i] == raw.heights[i]) {
                    adapted[i] = Integer.MIN_VALUE;
                }
            }
        }

        VirtualLevel.Terrain terrain = new VirtualLevel.Terrain() {
            @Override
            public int ground(int x, int z) {
                int i = x - groundMinX;
                int j = z - groundMinZ;
                // Pieces look past their box now and then (a shipwreck averages the ground under its unrotated
                // template): the terrain there is sampled on demand.
                return i < 0 || j < 0 || i >= groundSize || j >= groundSize
                        ? generator.ground(x, z) : ground[i + j * groundSize];
            }

            @Override
            public BlockState top(int x, int z) {
                return BiomeLook.of(raw.biomeAt(x, z)).top();
            }

            @Override
            public Holder<Biome> biome(int x, int y, int z) {
                return Math.abs(y - ground(x, z)) <= 8 ? raw.biomeAt(x, z) : sampler.biome(x, y, z);
            }
        };
        VirtualLevel level = new VirtualLevel(terrain, seed, minY, height, seaLevel, registries, dimensionType);
        placePieces(start, level);
        timings[3].add(System.nanoTime() - adaptDone);
        Built result = new Built(name(structure), box, start.getChunkPos(), columns(box, level.writes()),
                groundMinX, groundMinZ, groundSize, 1, adapted);
        if (DhTurbo.DEBUG) {
            DhTurbo.LOGGER.debug("Built {} at {} in {} ms", name(structure), box.getCenter(),
                    (System.nanoTime() - begin) / 1_000_000);
        }
        return result;
    }

    /** A structure in a world without a single surface: placed on the full column profiles, terrain unchanged. */
    private Built placeInProfile(StructureStart start) {
        long begin = System.nanoTime();
        BoundingBox box = start.getBoundingBox();
        ProfileTerrain.Columns columns = profile.columns();
        net.minecraft.world.level.levelgen.NoiseGeneratorSettings settings = generator.generatorSettings().value();
        VirtualLevel.Terrain terrain = new VirtualLevel.Terrain() {
            @Override
            public int ground(int x, int z) {
                return columns.top(x, z);
            }

            @Override
            public BlockState top(int x, int z) {
                return settings.defaultBlock();
            }

            @Override
            public Holder<Biome> biome(int x, int y, int z) {
                return sampler.biome(x, y, z);
            }

            @Override
            public Boolean solid(int x, int y, int z) {
                return columns.solid(x, y, z);
            }

            @Override
            public BlockState rock() {
                return settings.defaultBlock();
            }

            @Override
            public BlockState fluid() {
                return settings.defaultFluid();
            }
        };
        VirtualLevel level = new VirtualLevel(terrain, seed, minY, height, seaLevel, registries, dimensionType);
        placePieces(start, level);
        timings[3].add(System.nanoTime() - begin);
        return new Built(name(start.getStructure()), box, start.getChunkPos(), columns(box, level.writes()),
                box.minX(), box.minZ(), 1, 1, null);
    }

    /**
     * A structure for wide columns: terrain heights every {@link #COARSE_STEP} blocks on vanilla's cell corners, as the
     * wide tiles themselves have them, interpolated between; and instead of vanilla's terrain adaptation block by block,
     * the ground under each piece that adapts it set to the piece's floor. A village costs a fraction of the exact build
     * and looks the same in columns 4+ blocks wide.
     */
    private Built placeCoarse(StructureStart start) {
        long begin = System.nanoTime();
        BoundingBox box = start.getBoundingBox();
        Structure structure = start.getStructure();
        boolean small = Math.max(box.getXSpan(), box.getZSpan()) <= SMALL_SIZE;
        int cell = small ? SurfaceTile.CELL_WIDTH : COARSE_STEP;
        int groundMinX = Math.floorDiv(box.minX(), cell) * cell;
        int groundMinZ = Math.floorDiv(box.minZ(), cell) * cell;
        // Corners around the whole box, one beyond its far edge to interpolate up to it.
        int groundSize = Math.max(box.maxX() - groundMinX, box.maxZ() - groundMinZ) / cell + 2;
        // Small ones also get the cave-aware heights on the corners (a seabed cave lowers the ground a shipwreck
        // averages); cheap for their few corners.
        SurfaceTile raw = SurfaceTile.generate(sampler, groundMinX, groundMinZ, cell, groundSize, 1, small);
        int[] ground = raw.heights.clone();
        long terrainDone = System.nanoTime();
        timings[1].add(terrainDone - begin);
        int[] adapted = null;
        if (structure.terrainAdaptation() != TerrainAdjustment.NONE
                && flattenUnderPieces(start, groundMinX, groundMinZ, groundSize, cell, ground)) {
            adapted = ground.clone();
            for (int i = 0; i < adapted.length; i++) {
                if (adapted[i] == raw.heights[i]) {
                    adapted[i] = Integer.MIN_VALUE;
                }
            }
        }
        long adaptDone = System.nanoTime();
        timings[2].add(adaptDone - terrainDone);
        int maxIndex = groundSize - 2;
        VirtualLevel.Terrain terrain = new VirtualLevel.Terrain() {
            @Override
            public int ground(int x, int z) {
                int dx = x - groundMinX;
                int dz = z - groundMinZ;
                int i = Math.floorDiv(dx, cell);
                int j = Math.floorDiv(dz, cell);
                if (i < 0 || j < 0 || i > maxIndex || j > maxIndex) {
                    return generator.ground(x, z);
                }
                double fx = (dx - i * cell) / (double) cell;
                double fz = (dz - j * cell) / (double) cell;
                int row = j * groundSize;
                double near = ground[i + row] + (ground[i + 1 + row] - ground[i + row]) * fx;
                double far = ground[i + row + groundSize] + (ground[i + 1 + row + groundSize] - ground[i + row + groundSize]) * fx;
                return (int) Math.round(near + (far - near) * fz);
            }

            @Override
            public BlockState top(int x, int z) {
                return BiomeLook.of(raw.biomeAt(x, z)).top();
            }

            @Override
            public Holder<Biome> biome(int x, int y, int z) {
                return Math.abs(y - ground(x, z)) <= 8 ? raw.biomeAt(x, z) : sampler.biome(x, y, z);
            }
        };
        VirtualLevel level = new VirtualLevel(terrain, seed, minY, height, seaLevel, registries, dimensionType);
        placePieces(start, level);
        timings[3].add(System.nanoTime() - adaptDone);
        return new Built(name(structure), box, start.getChunkPos(), columns(box, level.writes()),
                groundMinX, groundMinZ, groundSize, cell, adapted);
    }

    /**
     * The coarse stand-in for terrain adaptation: corners under a piece that beards the terrain (village houses, temples)
     * take the piece's floor, as vanilla's beard fills below it and clears above. Buried structures keep the ground.
     * Returns whether any corner changed.
     */
    private static boolean flattenUnderPieces(StructureStart start, int groundMinX, int groundMinZ, int groundSize,
                                              int cell, int[] ground) {
        TerrainAdjustment structureAdjustment = start.getStructure().terrainAdaptation();
        boolean changed = false;
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox box;
            TerrainAdjustment adjustment;
            int delta;
            if (piece instanceof PieceBeardifierModifier modifier) {
                box = modifier.getBeardifierBox();
                adjustment = modifier.getTerrainAdjustment();
                delta = modifier.getGroundLevelDelta();
            } else if (piece instanceof PoolElementStructurePiece pool) {
                if (pool.getElement().getProjection() != StructureTemplatePool.Projection.RIGID) {
                    continue;
                }
                box = pool.getBoundingBox();
                adjustment = structureAdjustment;
                delta = pool.getGroundLevelDelta();
            } else {
                box = piece.getBoundingBox();
                adjustment = structureAdjustment;
                delta = 0;
            }
            if (adjustment == TerrainAdjustment.NONE || adjustment == TerrainAdjustment.BURY) {
                continue;
            }
            int floor = box.minY() + delta;
            int fromI = Math.max(0, Math.floorDiv(box.minX() - groundMinX + cell - 1, cell));
            int toI = Math.min(groundSize - 1, Math.floorDiv(box.maxX() - groundMinX, cell));
            int fromJ = Math.max(0, Math.floorDiv(box.minZ() - groundMinZ + cell - 1, cell));
            int toJ = Math.min(groundSize - 1, Math.floorDiv(box.maxZ() - groundMinZ, cell));
            for (int j = fromJ; j <= toJ; j++) {
                for (int i = fromI; i <= toI; i++) {
                    if (ground[i + j * groundSize] != floor) {
                        ground[i + j * groundSize] = floor;
                        changed = true;
                    }
                }
            }
        }
        return changed;
    }

    /** Places the pieces chunk by chunk with the random every chunk gives the structure, as chunk generation does. */
    private void placePieces(StructureStart start, VirtualLevel level) {
        BoundingBox box = start.getBoundingBox();
        Structure structure = start.getStructure();
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0));
        int index = stepIndex.getOrDefault(structure, 0);
        int step = structure.step().ordinal();
        List<StructurePiece> pieces = start.getPieces();
        BlockPos pivot = pivot(pieces);
        for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
            for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
                int blockX = chunkX << 4;
                int blockZ = chunkZ << 4;
                // ChunkGenerator.getWritableArea and the seed applyBiomeDecoration gives this structure in the chunk.
                BoundingBox area = new BoundingBox(blockX, minY + 1, blockZ, blockX + 15, minY + height - 1, blockZ + 15);
                ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                long decorationSeed = random.setDecorationSeed(seed, blockX, blockZ);
                random.setFeatureSeed(decorationSeed, index, step);
                for (StructurePiece piece : pieces) {
                    if (piece.getBoundingBox().intersects(area)) {
                        try {
                            piece.postProcess(level.view, null, generator, random, area, chunk, pivot);
                        } catch (VirtualLevel.NoEntities e) {
                            // Mobs come last; the piece's blocks are all there.
                        } catch (RuntimeException e) {
                            failures.increment();
                            report(piece.getType() + " in " + name(structure), e);
                        }
                    }
                }
                try {
                    structure.afterPlace(level.view, null, generator, random, area, chunk, new PiecesContainer(pieces));
                } catch (VirtualLevel.NoEntities e) {
                    // As above.
                } catch (RuntimeException e) {
                    failures.increment();
                    report("after-place of " + name(structure), e);
                }
            }
        }
    }

    private String name(Structure structure) {
        return String.valueOf(registries.registryOrThrow(Registries.STRUCTURE).getKey(structure));
    }

    /** StructureStart.placeInChunk: the first piece's center at its floor. */
    private static BlockPos pivot(List<StructurePiece> pieces) {
        BoundingBox first = pieces.get(0).getBoundingBox();
        BlockPos center = first.getCenter();
        return new BlockPos(center.getX(), first.minY(), center.getZ());
    }

    /**
     * The terrain as chunk generation shapes it around the structure: the final density with vanilla's
     * {@link Beardifier} added (per chunk, from the pieces and junctions within its reach, like
     * {@code Beardifier.forStructuresInChunk}). Returns whether any column changed.
     */
    private boolean adaptTerrain(StructureStart start, int groundMinX, int groundMinZ, int groundSize, int[] ground) {
        TerrainAdjustment adjustment = start.getStructure().terrainAdaptation();
        Long2DoubleMap corners = new Long2DoubleOpenHashMap();
        SurfaceSampler.CornerSource cornerSource = (x, y, z) -> {
            long key = BlockPos.asLong(x, y, z);
            double value = corners.getOrDefault(key, Double.NaN);
            if (Double.isNaN(value)) {
                value = sampler.realDensity(x, y, z);
                corners.put(key, value);
            }
            return value;
        };
        int maxY = sampler.maxY();
        boolean changed = false;
        int groundMaxX = groundMinX + groundSize - 1;
        int groundMaxZ = groundMinZ + groundSize - 1;
        for (int chunkX = groundMinX >> 4; chunkX <= groundMaxX >> 4; chunkX++) {
            for (int chunkZ = groundMinZ >> 4; chunkZ <= groundMaxZ >> 4; chunkZ++) {
                ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
                ObjectList<Beardifier.Rigid> rigids = new ObjectArrayList<>();
                ObjectList<JigsawJunction> junctions = new ObjectArrayList<>();
                collectBeard(start, adjustment, chunk, rigids, junctions);
                if (rigids.isEmpty() && junctions.isEmpty()) {
                    continue;
                }
                int low = Integer.MAX_VALUE;
                int high = Integer.MIN_VALUE;
                for (Beardifier.Rigid rigid : rigids) {
                    low = Math.min(low, rigid.box().minY() + rigid.groundLevelDelta());
                    high = Math.max(high, Math.max(rigid.box().minY() + rigid.groundLevelDelta(), rigid.box().maxY()));
                }
                for (JigsawJunction junction : junctions) {
                    low = Math.min(low, junction.getSourceGroundY());
                    high = Math.max(high, junction.getSourceGroundY());
                }
                Beardifier beard = new Beardifier(rigids.iterator(), junctions.iterator());
                for (int x = Math.max(chunk.getMinBlockX(), groundMinX); x <= Math.min(chunk.getMaxBlockX(), groundMaxX); x++) {
                    for (int z = Math.max(chunk.getMinBlockZ(), groundMinZ); z <= Math.min(chunk.getMaxBlockZ(), groundMaxZ); z++) {
                        int index = (x - groundMinX) + (z - groundMinZ) * groundSize;
                        int raw = ground[index];
                        int from = Math.min(maxY - 1, Math.max(raw, high + Beardifier.BEARD_KERNEL_RADIUS));
                        int to = Math.max(minY, Math.min(raw, low - Beardifier.BEARD_KERNEL_RADIUS) - 1);
                        int surface = raw;
                        for (int y = from; y >= to; y--) {
                            double density = sampler.blockDensity(x, y, z, cornerSource)
                                    + beard.compute(new DensityFunction.SinglePointContext(x, y, z));
                            if (density > 0) {
                                surface = y + 1;
                                break;
                            }
                        }
                        if (surface != raw) {
                            ground[index] = surface;
                            changed = true;
                        }
                    }
                }
            }
        }
        return changed;
    }

    /** The pieces and junctions of one structure near a chunk, as {@code Beardifier.forStructuresInChunk} takes them. */
    private static void collectBeard(StructureStart start, TerrainAdjustment adjustment, ChunkPos chunk,
                                     ObjectList<Beardifier.Rigid> rigids, ObjectList<JigsawJunction> junctions) {
        int minX = chunk.getMinBlockX();
        int minZ = chunk.getMinBlockZ();
        for (StructurePiece piece : start.getPieces()) {
            if (!piece.isCloseToChunk(chunk, Beardifier.BEARD_KERNEL_RADIUS)) {
                continue;
            }
            if (piece instanceof PieceBeardifierModifier modifier) {
                if (modifier.getTerrainAdjustment() != TerrainAdjustment.NONE) {
                    rigids.add(new Beardifier.Rigid(modifier.getBeardifierBox(), modifier.getTerrainAdjustment(),
                            modifier.getGroundLevelDelta()));
                }
            } else if (piece instanceof PoolElementStructurePiece pool) {
                if (pool.getElement().getProjection() == StructureTemplatePool.Projection.RIGID) {
                    rigids.add(new Beardifier.Rigid(pool.getBoundingBox(), adjustment, pool.getGroundLevelDelta()));
                }
                for (JigsawJunction junction : pool.getJunctions()) {
                    int x = junction.getSourceX();
                    int z = junction.getSourceZ();
                    if (x > minX - 12 && z > minZ - 12 && x < minX + 15 + 12 && z < minZ + 15 + 12) {
                        junctions.add(junction);
                    }
                }
            } else {
                rigids.add(new Beardifier.Rigid(piece.getBoundingBox(), adjustment, 0));
            }
        }
    }

    private void report(String what, Exception e) {
        String key = what + ": " + e;
        if (REPORTED.size() < 20 && REPORTED.putIfAbsent(key, Boolean.TRUE) == null) {
            DhTurbo.LOGGER.info("A structure failed on distant terrain and is skipped there: {}", key, e);
        }
    }

    /** The recorded blocks of a structure's box as columns of runs. */
    private static Column[] columns(BoundingBox box, Long2ObjectMap<BlockState> writes) {
        int sizeX = box.getXSpan();
        int sizeZ = box.getZSpan();
        // Y lists per column, then sorted runs.
        Long2ObjectMap<List<long[]>> perColumn = new Long2ObjectOpenHashMap<>();
        List<BlockState> states = new ArrayList<>();
        Object2IntMap<BlockState> stateIds = new Object2IntOpenHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Long2ObjectMap.Entry<BlockState> entry : writes.long2ObjectEntrySet()) {
            pos.set(entry.getLongKey());
            int i = pos.getX() - box.minX();
            int j = pos.getZ() - box.minZ();
            if (i < 0 || j < 0 || i >= sizeX || j >= sizeZ) {
                continue;
            }
            int id = stateIds.computeIfAbsent(entry.getValue(), key -> {
                states.add(entry.getValue());
                return states.size() - 1;
            });
            perColumn.computeIfAbsent(i + (long) j * sizeX, key -> new ArrayList<>())
                    .add(new long[]{pos.getY(), id});
        }
        Column[] columns = new Column[sizeX * sizeZ];
        for (Long2ObjectMap.Entry<List<long[]>> entry : perColumn.long2ObjectEntrySet()) {
            List<long[]> blocks = entry.getValue();
            blocks.sort((a, b) -> Long.compare(a[0], b[0]));
            int[] bottoms = new int[blocks.size()];
            int[] tops = new int[blocks.size()];
            BlockState[] runStates = new BlockState[blocks.size()];
            int runs = 0;
            int top = Integer.MIN_VALUE;
            for (long[] block : blocks) {
                int y = (int) block[0];
                BlockState state = states.get((int) block[1]);
                if (runs > 0 && tops[runs - 1] == y && runStates[runs - 1] == state) {
                    tops[runs - 1] = y + 1;
                } else {
                    bottoms[runs] = y;
                    tops[runs] = y + 1;
                    runStates[runs] = state;
                    runs++;
                }
                if (!state.isAir()) {
                    top = y + 1;
                }
            }
            columns[(int) entry.getLongKey()] = new Column(Arrays.copyOf(bottoms, runs), Arrays.copyOf(tops, runs),
                    Arrays.copyOf(runStates, runs), top);
        }
        return columns;
    }
}
