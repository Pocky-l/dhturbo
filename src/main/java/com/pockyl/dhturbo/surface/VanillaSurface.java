package com.pockyl.dhturbo.surface;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.Arrays;

/**
 * The world's own surface rules on columns that were never generated as chunks: grass, dirt, sand, gravel shores,
 * calcite, coarse dirt, mud, snow and ice on mountains, badlands clay bands, plus vanilla's two surface extensions
 * (badlands hoodoos, frozen ocean icebergs). Works for every data pack and mod that defines its surface with rules.
 * <p>
 * A column is laid out the way the noise step leaves it (rock up to the ground, water up to sea level, air above) and
 * then goes through the same loop as {@code SurfaceSystem.buildSurface}, but only over the top {@link #DEPTH} blocks:
 * deeper rules (deepslate, bedrock) are never seen from afar. Caves are ignored, so the rock reaches down to the bottom
 * of the world.
 * <p>
 * Not thread-safe: every tile uses its own {@link Pass}.
 */
public final class VanillaSurface {
    /** Blocks below the surface that get rules; badlands cliffs show their bands deeper. */
    private static final int DEPTH = 8;
    private static final int BADLANDS_DEPTH = 96;
    /**
     * A pass costs about a millisecond to set up, so each thread reuses its own; renewed now and then, since the noise
     * chunk inside keeps every preliminary surface level it computed.
     */
    private static final int PASS_REUSE = 32;
    /** Biome quarts kept per pass (they never change, but the cache must not grow without end). */
    private static final int MAX_CACHED_QUARTS = 1 << 16;

    private final SurfaceSampler sampler;
    private final SurfaceRules.RuleSource rules;
    private final NoiseGeneratorSettings settings;
    private final SurfaceSystem system;
    private final Registry<Biome> biomes;
    private final WorldGenerationContext context;
    private final LevelHeightAccessor heightAccessor;
    private final BlockState defaultBlock;
    private final long zoomSeed;
    private final ThreadLocal<Pass> passes = new ThreadLocal<>();

    public VanillaSurface(NoiseBasedChunkGenerator generator, SurfaceSampler sampler, RegistryAccess registries, long seed) {
        this.sampler = sampler;
        this.settings = generator.generatorSettings().value();
        this.rules = settings.surfaceRule();
        this.system = sampler.randomState().surfaceSystem();
        this.biomes = registries.registryOrThrow(Registries.BIOME);
        this.heightAccessor = LevelHeightAccessor.create(sampler.minY(), sampler.maxY() - sampler.minY());
        this.context = new WorldGenerationContext(generator, heightAccessor);
        this.defaultBlock = settings.defaultBlock();
        this.zoomSeed = BiomeManager.obfuscateSeed(seed);
    }

    /** Ground heights the {@code steep} rule compares. */
    public interface Heights {
        /** First free Y above the column holding the block (water counts, like vanilla's heightmap at that step). */
        int at(int x, int z);
    }

    /** Starts the columns of one tile. */
    /**
     * @param exactBiomes every block's own biome (detailed tiles); otherwise the column takes the biome at its surface
     *                    for all its blocks, since wide columns rarely share a biome quart and sampling it per block
     *                    cost more than the rest of the tile
     */
    public Pass pass(int originX, int originZ, Heights heights, boolean exactBiomes) {
        Pass pass = passes.get();
        if (pass == null || ++pass.uses > PASS_REUSE) {
            pass = new Pass(originX, originZ);
            passes.set(pass);
        }
        pass.heights = heights;
        pass.exactBiomes = exactBiomes;
        if (pass.quarts.size() > MAX_CACHED_QUARTS) {
            pass.quarts.clear();
        }
        return pass;
    }

    /** A column after the surface step: blocks from {@link #bottom} (inclusive) to {@link #top} (exclusive). */
    public static final class Column {
        private BlockState[] blocks = new BlockState[0];
        private int bottom;
        private int top;

        public int bottom() {
            return bottom;
        }

        public int top() {
            return top;
        }

        public BlockState at(int y) {
            return blocks[y - bottom];
        }
    }

    /** The surface rules set up for one thread; see {@link #pass}. Use it on that thread only, one tile at a time. */
    public final class Pass {
        private final Long2ObjectOpenHashMap<Holder<Biome>> quarts = new Long2ObjectOpenHashMap<>();
        private int uses;
        private Heights heights;
        private boolean exactBiomes;
        private final Column column = new Column();
        private final SurfaceRules.Context rulesContext;
        private final SurfaceRules.SurfaceRule rule;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private final BiomeManager biomeManager;
        private int columnX;
        private int columnZ;
        private Holder<Biome> columnBiome;
        private final BlockColumn blockColumn = new BlockColumn() {
            @Override
            public BlockState getBlock(int y) {
                if (y < column.bottom) {
                    return y < sampler.minY() ? Blocks.VOID_AIR.defaultBlockState() : defaultBlock;
                }
                return y >= column.top ? Blocks.AIR.defaultBlockState() : column.blocks[y - column.bottom];
            }

            @Override
            public void setBlock(int y, BlockState state) {
                if (y >= column.bottom && y < sampler.maxY()) {
                    grow(y + 1);
                    column.blocks[y - column.bottom] = state;
                }
            }
        };

        private Pass(int originX, int originZ) {
            // Biomes per quart, cached: the rules ask for the biome of nearly every block they look at.
            this.biomeManager = new BiomeManager((qx, qy, qz) -> quarts.computeIfAbsent(BlockPos.asLong(qx, qy, qz),
                    key -> sampler.noiseBiome(qx, qy, qz)), zoomSeed);
            // The chunk only answers the steep rule's heightmap questions, in local coordinates of the column's chunk.
            ProtoChunk chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, heightAccessor, biomes, null) {
                @Override
                public int getHeight(Heightmap.Types type, int x, int z) {
                    return heights.at((columnX & ~15) | x, (columnZ & ~15) | z) - 1;
                }
            };
            // Only its preliminary surface level is used (the abovePreliminarySurface rule, icebergs).
            NoiseChunk noiseChunk = new NoiseChunk(1, sampler.randomState(), originX, originZ, settings.noiseSettings(),
                    DensityFunctions.BeardifierMarker.INSTANCE, settings,
                    (x, y, z) -> new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid()), Blender.empty());
            this.rulesContext = new SurfaceRules.Context(system, sampler.randomState(), chunk, noiseChunk,
                    pos -> exactBiomes ? biomeManager.getBiome(pos) : columnBiome, biomes, context);
            this.rule = rules.apply(rulesContext);
        }

        /** The column's biome as the level reports it (vanilla's fuzzy zoom of the noise biomes). */
        public Holder<Biome> biome(int x, int y, int z) {
            return biomeManager.getBiome(cursor.set(x, y, z));
        }

        /**
         * Runs the surface step on the column at (x, z) whose rock ends below {@code ground} (first non-solid Y).
         * The result is valid until the next call.
         */
        public Column column(int x, int z, int ground) {
            int seaLevel = sampler.seaLevel();
            int minY = sampler.minY();
            columnX = x;
            columnZ = z;
            int surface = Math.max(ground, seaLevel);
            Holder<Biome> topBiome = biome(x, surface, z);
            columnBiome = topBiome;
            boolean badlands = topBiome.is(Biomes.BADLANDS) || topBiome.is(Biomes.ERODED_BADLANDS)
                    || topBiome.is(Biomes.WOODED_BADLANDS);
            column.bottom = Math.max(minY, Math.min(ground, seaLevel) - (badlands ? BADLANDS_DEPTH : DEPTH));
            column.top = column.bottom;
            grow(surface);
            Arrays.fill(column.blocks, 0, Math.max(0, ground - column.bottom), defaultBlock);
            if (ground < seaLevel) {
                Arrays.fill(column.blocks, Math.max(0, ground - column.bottom), seaLevel - column.bottom,
                        settings.defaultFluid());
            }
            if (topBiome.is(Biomes.ERODED_BADLANDS)) {
                system.erodedBadlandsExtension(blockColumn, x, z, surface, heightAccessor);
            }
            rulesContext.updateXZ(x, z);
            int stoneDepthAbove = 0;
            int waterHeight = Integer.MIN_VALUE;
            for (int y = column.top - 1; y >= column.bottom; y--) {
                BlockState state = column.blocks[y - column.bottom];
                if (state.isAir()) {
                    stoneDepthAbove = 0;
                    waterHeight = Integer.MIN_VALUE;
                } else if (!state.getFluidState().isEmpty()) {
                    if (waterHeight == Integer.MIN_VALUE) {
                        waterHeight = y + 1;
                    }
                } else {
                    stoneDepthAbove++;
                    // Without caves the rock reaches the bottom of the world.
                    rulesContext.updateY(stoneDepthAbove, y - minY + 1, waterHeight, x, y, z);
                    if (state == defaultBlock) {
                        BlockState replaced = rule.tryApply(x, y, z);
                        if (replaced != null) {
                            column.blocks[y - column.bottom] = replaced;
                        }
                    }
                }
            }
            if (topBiome.is(Biomes.FROZEN_OCEAN) || topBiome.is(Biomes.DEEP_FROZEN_OCEAN)) {
                system.frozenOceanExtension(rulesContext.getMinSurfaceLevel(), topBiome.value(), blockColumn,
                        new BlockPos.MutableBlockPos(), x, z, surface);
            }
            // The extensions may leave air on top.
            while (column.top > column.bottom && column.blocks[column.top - 1 - column.bottom].isAir()) {
                column.top--;
            }
            return column;
        }

        /** Raises the column's top to {@code top} (exclusive), filling with air. */
        private void grow(int top) {
            if (top <= column.top) {
                return;
            }
            int size = top - column.bottom;
            if (column.blocks.length < size) {
                column.blocks = Arrays.copyOf(column.blocks, Math.max(size, column.blocks.length * 2));
            }
            Arrays.fill(column.blocks, column.top - column.bottom, size, Blocks.AIR.defaultBlockState());
            column.top = top;
        }
    }
}
