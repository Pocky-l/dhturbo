package com.pockyl.fatamorgana.surface;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How a biome looks from afar: its top block, the floor under water and its trees.
 * <p>
 * Distant Horizons learns surface blocks by generating real chunks for every new biome; this is the cheap
 * alternative: a hand-made table for vanilla biomes and common tags for modded ones. Grass and leaves are tinted by
 * the biome when Distant Horizons colours them, so one block per biome is enough.
 *
 * @param treeCoverage share of the ground under canopy, 0 for treeless biomes
 */
public record BiomeLook(BlockState top, BlockState underwater, @Nullable Tree tree, double treeCoverage) {
    private static final Map<Holder<Biome>, BiomeLook> CACHE = new ConcurrentHashMap<>();

    /**
     * Tree shape: canopy of {@code leaves} with a {@code log} trunk; heights in blocks above the ground.
     *
     * @param radius canopy radius in blocks
     */
    public record Tree(BlockState leaves, BlockState log, int minHeight, int maxHeight, double radius) {
        static Tree of(Block leaves, Block log, int minHeight, int maxHeight, double radius) {
            return new Tree(leaves.defaultBlockState(), log.defaultBlockState(), minHeight, maxHeight, radius);
        }
    }

    private static final Tree OAK = Tree.of(Blocks.OAK_LEAVES, Blocks.OAK_LOG, 5, 7, 2.5);
    private static final Tree BIRCH = Tree.of(Blocks.BIRCH_LEAVES, Blocks.BIRCH_LOG, 6, 9, 2.0);
    private static final Tree SPRUCE = Tree.of(Blocks.SPRUCE_LEAVES, Blocks.SPRUCE_LOG, 7, 14, 2.0);
    private static final Tree OLD_SPRUCE = Tree.of(Blocks.SPRUCE_LEAVES, Blocks.SPRUCE_LOG, 14, 28, 3.0);
    private static final Tree DARK_OAK = Tree.of(Blocks.DARK_OAK_LEAVES, Blocks.DARK_OAK_LOG, 6, 9, 3.5);
    private static final Tree JUNGLE = Tree.of(Blocks.JUNGLE_LEAVES, Blocks.JUNGLE_LOG, 8, 22, 3.5);
    private static final Tree ACACIA = Tree.of(Blocks.ACACIA_LEAVES, Blocks.ACACIA_LOG, 5, 8, 3.0);
    private static final Tree CHERRY = Tree.of(Blocks.CHERRY_LEAVES, Blocks.CHERRY_LOG, 5, 8, 3.0);
    private static final Tree MANGROVE = Tree.of(Blocks.MANGROVE_LEAVES, Blocks.MANGROVE_LOG, 6, 10, 3.0);

    public static BiomeLook of(Holder<Biome> biome) {
        return CACHE.computeIfAbsent(biome, BiomeLook::create);
    }

    private static BiomeLook create(Holder<Biome> biome) {
        BlockState top = topBlock(biome).defaultBlockState();
        BlockState underwater = underwaterBlock(biome).defaultBlockState();
        if (is(biome, Biomes.DARK_FOREST)) {
            return new BiomeLook(top, underwater, DARK_OAK, 0.85);
        }
        if (is(biome, Biomes.BIRCH_FOREST) || is(biome, Biomes.OLD_GROWTH_BIRCH_FOREST)) {
            return new BiomeLook(top, underwater, BIRCH, 0.55);
        }
        if (is(biome, Biomes.CHERRY_GROVE)) {
            return new BiomeLook(top, underwater, CHERRY, 0.3);
        }
        if (is(biome, Biomes.MANGROVE_SWAMP)) {
            return new BiomeLook(top, underwater, MANGROVE, 0.6);
        }
        if (is(biome, Biomes.SWAMP)) {
            return new BiomeLook(top, underwater, OAK, 0.25);
        }
        if (is(biome, Biomes.OLD_GROWTH_PINE_TAIGA) || is(biome, Biomes.OLD_GROWTH_SPRUCE_TAIGA)) {
            return new BiomeLook(top, underwater, OLD_SPRUCE, 0.6);
        }
        if (is(biome, Biomes.WINDSWEPT_FOREST)) {
            return new BiomeLook(top, underwater, SPRUCE, 0.35);
        }
        if (is(biome, Biomes.GROVE)) {
            return new BiomeLook(top, underwater, SPRUCE, 0.45);
        }
        if (is(biome, Biomes.WOODED_BADLANDS)) {
            return new BiomeLook(top, underwater, OAK, 0.2);
        }
        if (is(biome, Biomes.MEADOW) || is(biome, Biomes.PLAINS) || is(biome, Biomes.SUNFLOWER_PLAINS)) {
            return new BiomeLook(top, underwater, OAK, 0.02);
        }
        if (is(biome, Biomes.SNOWY_PLAINS) || is(biome, Biomes.WINDSWEPT_HILLS)) {
            return new BiomeLook(top, underwater, SPRUCE, 0.03);
        }
        if (biome.is(BiomeTags.IS_JUNGLE)) {
            return new BiomeLook(top, underwater, JUNGLE, is(biome, Biomes.SPARSE_JUNGLE) ? 0.3 : 0.75);
        }
        if (biome.is(BiomeTags.IS_SAVANNA)) {
            return new BiomeLook(top, underwater, ACACIA, 0.07);
        }
        if (biome.is(BiomeTags.IS_TAIGA) || biome.is(Tags.Biomes.IS_CONIFEROUS_TREE)) {
            return new BiomeLook(top, underwater, SPRUCE, 0.5);
        }
        if (biome.is(BiomeTags.IS_FOREST) || biome.is(Tags.Biomes.IS_DENSE_VEGETATION_OVERWORLD)) {
            return new BiomeLook(top, underwater, OAK, 0.5);
        }
        return new BiomeLook(top, underwater, null, 0);
    }

    private static Block topBlock(Holder<Biome> biome) {
        if (is(biome, Biomes.MUSHROOM_FIELDS) || biome.is(Tags.Biomes.IS_MUSHROOM)) {
            return Blocks.MYCELIUM;
        }
        if (biome.is(BiomeTags.IS_BADLANDS)) {
            return is(biome, Biomes.WOODED_BADLANDS) ? Blocks.COARSE_DIRT : Blocks.RED_SAND;
        }
        if (biome.is(BiomeTags.IS_BEACH) || biome.is(Tags.Biomes.IS_DESERT) || biome.is(Tags.Biomes.IS_SANDY)) {
            return Blocks.SAND;
        }
        if (is(biome, Biomes.STONY_SHORE) || is(biome, Biomes.STONY_PEAKS)) {
            return Blocks.STONE;
        }
        if (is(biome, Biomes.JAGGED_PEAKS) || is(biome, Biomes.FROZEN_PEAKS) || is(biome, Biomes.SNOWY_SLOPES)
                || is(biome, Biomes.ICE_SPIKES)) {
            return Blocks.SNOW_BLOCK;
        }
        if (is(biome, Biomes.WINDSWEPT_GRAVELLY_HILLS)) {
            return Blocks.GRAVEL;
        }
        if (is(biome, Biomes.MANGROVE_SWAMP)) {
            return Blocks.MUD;
        }
        if (is(biome, Biomes.OLD_GROWTH_PINE_TAIGA) || is(biome, Biomes.OLD_GROWTH_SPRUCE_TAIGA)) {
            return Blocks.PODZOL;
        }
        if (!biome.is(BiomeTags.IS_OVERWORLD)) {
            return Blocks.STONE;
        }
        return Blocks.GRASS_BLOCK;
    }

    private static Block underwaterBlock(Holder<Biome> biome) {
        if (is(biome, Biomes.MANGROVE_SWAMP) || is(biome, Biomes.SWAMP)) {
            return Blocks.MUD;
        }
        if (is(biome, Biomes.COLD_OCEAN) || is(biome, Biomes.FROZEN_OCEAN) || is(biome, Biomes.DEEP_COLD_OCEAN)
                || is(biome, Biomes.DEEP_FROZEN_OCEAN) || is(biome, Biomes.DEEP_OCEAN) || is(biome, Biomes.OCEAN)) {
            return Blocks.GRAVEL;
        }
        return Blocks.SAND;
    }

    private static boolean is(Holder<Biome> biome, ResourceKey<Biome> key) {
        return biome.is(key);
    }
}
