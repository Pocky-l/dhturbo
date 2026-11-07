package com.pockyl.dhturbo.surface;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BlockColumn;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.rule.ConditionRule;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;

import java.util.Arrays;
import java.util.List;

/**
 * The world's own material rules on columns that were never generated as chunks: grass, dirt, sand, gravel shores,
 * calcite, coarse dirt, mud, snow and ice on mountains, badlands clay bands, plus vanilla's two surface extensions
 * (badlands hoodoos, frozen ocean icebergs). Works for every data pack and mod that defines its surface with rules.
 * <p>
 * A column is laid out the way the noise step leaves it (rock up to the ground, water up to sea level, air above) and
 * then goes through the same loop as {@code MaterialSystem.buildSurface}, but only over the top {@link #DEPTH} blocks:
 * deeper rules (deepslate, bedrock) are never seen from afar. Caves are ignored, so the rock reaches down to the bottom
 * of the world. Ore veins are material rules too, but they are left out: they cost more than the rest of a column and
 * only show on the sea floor, where nobody looks from afar.
 * <p>
 * Not thread-safe: every tile uses its own {@link Pass}.
 */
public final class VanillaSurface {
    /** Blocks below the surface that get rules; badlands cliffs show their bands deeper. */
    private static final int DEPTH = 8;
    private static final int BADLANDS_DEPTH = 96;
    /** A pass takes a while to set up, so each thread reuses its own; renewed now and then, as its biomes pile up. */
    private static final int PASS_REUSE = 32;
    /** Biome quarts kept per pass (they never change, but the cache must not grow without end). */
    private static final int MAX_CACHED_QUARTS = 1 << 16;

    private final SurfaceSampler sampler;
    private final MaterialRule rules;
    private final NoiseGeneratorSettings settings;
    private final MaterialSystem system;
    private final WorldGenerationContext context;
    private final LevelHeightAccessor heightAccessor;
    private final BlockState defaultBlock;
    private final long zoomSeed;
    private final ThreadLocal<Pass> passes = new ThreadLocal<>();

    public VanillaSurface(NoiseBasedChunkGenerator generator, SurfaceSampler sampler, long seed) {
        this.sampler = sampler;
        this.settings = generator.generatorSettings().value();
        this.rules = withoutVeins(settings.materialRule().value());
        this.system = sampler.randomState().surfaceSystem();
        this.heightAccessor = LevelHeightAccessor.create(sampler.minY(), sampler.maxY() - sampler.minY());
        this.context = new WorldGenerationContext(generator, heightAccessor);
        this.defaultBlock = settings.defaultBlock();
        this.zoomSeed = BiomeManager.obfuscateSeed(seed);
    }

    /** The rule without its ore veins; vanilla's stand at the top of its sequence, beside the surface. */
    private static MaterialRule withoutVeins(MaterialRule rule) {
        return switch (rule) {
            case MaterialRule.HolderHolder(Holder<MaterialRule> holder) -> withoutVeins(holder.value());
            case ConditionRule(var condition, MaterialRule then) -> new ConditionRule(condition, withoutVeins(then));
            case SequenceRule(List<MaterialRule> sequence) -> new SequenceRule(sequence.stream()
                    .filter(entry -> !veins(entry)).map(VanillaSurface::withoutVeins).toList());
            default -> rule;
        };
    }

    private static boolean veins(MaterialRule rule) {
        return switch (rule) {
            case MaterialRule.HolderHolder(Holder<MaterialRule> holder) -> veins(holder.value());
            case ConditionRule(var condition, MaterialRule then) -> veins(then);
            case OreVeinRule vein -> true;
            default -> false;
        };
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
        private Holder<Biome> biome;

        public int bottom() {
            return bottom;
        }

        public int top() {
            return top;
        }

        public BlockState at(int y) {
            return blocks[y - bottom];
        }

        /** The biome at the column's surface, as the level reports it (with vanilla's fuzzy zoom). */
        public Holder<Biome> biome() {
            return biome;
        }

        /** A copy that stays valid after the next column of its pass. */
        public Column copy() {
            Column copy = new Column();
            copy.blocks = Arrays.copyOf(blocks, Math.max(0, top - bottom));
            copy.bottom = bottom;
            copy.top = top;
            copy.biome = biome;
            return copy;
        }
    }

    /** The material rules set up for one thread; see {@link #pass}. Use it on that thread only, one tile at a time. */
    public final class Pass {
        private final Long2ObjectOpenHashMap<Holder<Biome>> quarts = new Long2ObjectOpenHashMap<>();
        private int uses;
        private Heights heights;
        private boolean exactBiomes;
        private final Column column = new Column();
        private final MaterialRuleContext rulesContext;
        private final RuleEvaluator rule;
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
            // The volume only says what the context may fill ahead of time; a column is answered one value at a time.
            this.rulesContext = new MaterialRuleContext(system, sampler.randomState(),
                    new DensityVolume(1, 1, 1, originX, sampler.minY(), originZ), sampler.samplers(),
                    pos -> exactBiomes ? biomeManager.getBiome(pos) : columnBiome, context, null);
            this.rule = rules.compile(rulesContext);
        }

        /** Hands the column to the rules, with the height differences the {@code steep} rule compares inside its chunk. */
        private void startColumn(int x, int z) {
            int chunkX = x & ~15;
            int chunkZ = z & ~15;
            int localX = x & 15;
            int localZ = z & 15;
            rulesContext.updateXZ(x, z,
                    heights.at(chunkX | Math.min(localX + 1, 15), z) - heights.at(chunkX | Math.max(localX - 1, 0), z),
                    heights.at(x, chunkZ | Math.min(localZ + 1, 15)) - heights.at(x, chunkZ | Math.max(localZ - 1, 0)));
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
            column.biome = topBiome;
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
            startColumn(x, z);
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
                    rulesContext.updateY(stoneDepthAbove, y - minY + 1, waterHeight, y);
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

        /**
         * Runs the surface step on a whole column of a world without a single surface (the Nether, the End): solid
         * blocks from {@code solid} (index 0 = the bottom of the world), the default fluid in open blocks below sea
         * level, then the rules over every block with vanilla's stone depths above and below, like
         * {@code MaterialSystem.buildSurface}. The result is valid until the next call.
         */
        public Column profileColumn(int x, int z, boolean[] solid) {
            int seaLevel = sampler.seaLevel();
            int minY = sampler.minY();
            columnX = x;
            columnZ = z;
            columnBiome = biome(x, Math.max(seaLevel, minY), z);
            column.bottom = minY;
            column.top = minY;
            grow(minY + solid.length);
            BlockState fluid = settings.defaultFluid();
            for (int i = 0; i < solid.length; i++) {
                int y = minY + i;
                column.blocks[i] = solid[i] ? defaultBlock
                        : y < seaLevel ? (y < Math.min(-54, seaLevel) ? Blocks.LAVA.defaultBlockState() : fluid)
                        : Blocks.AIR.defaultBlockState();
            }
            startColumn(x, z);
            int stoneDepthAbove = 0;
            int waterHeight = Integer.MIN_VALUE;
            int stoneBottom = Integer.MAX_VALUE;
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
                    if (stoneBottom >= y) {
                        // The bottom of this run of rock (vanilla scans down for the first block that is not stone).
                        stoneBottom = Integer.MIN_VALUE + 1;
                        for (int below = y - 1; below >= minY - 1; below--) {
                            if (below < minY || !solid[below - minY]) {
                                stoneBottom = below + 1;
                                break;
                            }
                        }
                    }
                    stoneDepthAbove++;
                    rulesContext.updateY(stoneDepthAbove, y - stoneBottom + 1, waterHeight, y);
                    if (state == defaultBlock) {
                        BlockState replaced = rule.tryApply(x, y, z);
                        if (replaced != null) {
                            column.blocks[y - column.bottom] = replaced;
                        }
                    }
                }
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
