package com.pockyl.dhturbo.surface;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.minecraftforge.common.Tags;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grass and flowers on distant ground. Distant Horizons does not draw plants as blocks: it tints the block under them
 * with their colour, so a plant per column gives meadows the speckled look real chunks have.
 * <p>
 * A hash of the position decides whether a column has a plant, with densities per biome. Flower species come from the
 * biome's own flower features (the ones bone meal uses): their block state providers are mostly noise-based, so tulip
 * fields and flower forest patches appear where the game puts them.
 */
public final class GroundCover {
    private static final Map<Holder<Biome>, Cover> CACHE = new ConcurrentHashMap<>();
    private static final BlockState SHORT_GRASS = Blocks.GRASS.defaultBlockState();
    private static final BlockState FERN = Blocks.FERN.defaultBlockState();
    private static final BlockState DEAD_BUSH = Blocks.DEAD_BUSH.defaultBlockState();

    /**
     * Plants of a biome.
     *
     * @param flowers    share of grass columns with a flower
     * @param grass      share of grass columns with grass or ferns
     * @param ferns      share of those that are ferns
     * @param deadBushes share of sand columns with a dead bush
     */
    private record Cover(List<BlockStateProvider> flowerProviders, double flowers, double grass, double ferns,
                         double deadBushes) {
    }

    private GroundCover() {
    }

    /**
     * The plant standing on a column, or null.
     *
     * @param biome      biome with its generation settings (the generator's holder)
     * @param levelBiome the same biome as the level knows it (carries tags on a client)
     * @param ground     top block of the column
     */
    @Nullable
    public static BlockState at(long seed, int x, int y, int z, Holder<Biome> biome, Holder<Biome> levelBiome,
                                BlockState ground) {
        Cover cover = CACHE.computeIfAbsent(levelBiome, key -> create(biome, levelBiome));
        Block block = ground.getBlock();
        long hash = FakeTrees.hash(seed ^ 0x5DEECE66DL, x, z);
        double roll = (hash >>> 40) / (double) (1L << 24);
        if (block == Blocks.GRASS_BLOCK) {
            if (roll < cover.flowers && !cover.flowerProviders.isEmpty()) {
                return flower(cover, hash, x, y, z);
            }
            if (roll < cover.flowers + cover.grass) {
                double kind = (hash & 0xFFFF) / 65536.0;
                return kind < cover.ferns ? FERN : SHORT_GRASS;
            }
            return null;
        }
        if (block == Blocks.PODZOL) {
            return roll < cover.grass ? FERN : null;
        }
        if (block == Blocks.SAND || block == Blocks.RED_SAND || block == Blocks.TERRACOTTA) {
            return roll < cover.deadBushes ? DEAD_BUSH : null;
        }
        return null;
    }

    private static BlockState flower(Cover cover, long hash, int x, int y, int z) {
        List<BlockStateProvider> providers = cover.flowerProviders;
        BlockStateProvider provider = providers.get((int) Math.floorMod(hash >>> 16, (long) providers.size()));
        try {
            return provider.getState(new XoroshiroRandomSource(hash), new BlockPos(x, y, z));
        } catch (RuntimeException e) {
            // Providers needing a level (rule based) cannot run here; plain grass instead.
            return SHORT_GRASS;
        }
    }

    private static Cover create(Holder<Biome> biome, Holder<Biome> levelBiome) {
        List<BlockStateProvider> flowers = biome.value().getGenerationSettings().getFlowerFeatures().stream()
                // A flower feature is a random patch whose placed feature holds the block provider; patches do not
                // list their inner features in getFeatures, so step in explicitly.
                .map(ConfiguredFeature::config)
                .filter(RandomPatchConfiguration.class::isInstance)
                .flatMap(config -> ((RandomPatchConfiguration) config).feature().value().getFeatures())
                .map(ConfiguredFeature::config)
                .filter(SimpleBlockConfiguration.class::isInstance)
                .map(config -> ((SimpleBlockConfiguration) config).toPlace())
                .toList();
        // Densities eyeballed from the vanilla patch features (tries and counts per chunk).
        if (is(levelBiome, Biomes.FLOWER_FOREST)) {
            return new Cover(flowers, 0.3, 0.25, 0, 0);
        }
        if (is(levelBiome, Biomes.MEADOW)) {
            return new Cover(flowers, 0.12, 0.55, 0, 0);
        }
        if (is(levelBiome, Biomes.CHERRY_GROVE)) {
            return new Cover(flowers, 0.2, 0.3, 0, 0);
        }
        if (is(levelBiome, Biomes.SUNFLOWER_PLAINS)) {
            return new Cover(flowers, 0.05, 0.5, 0, 0);
        }
        if (is(levelBiome, Biomes.PLAINS)) {
            return new Cover(flowers, 0.025, 0.45, 0, 0);
        }
        if (is(levelBiome, Biomes.SWAMP)) {
            return new Cover(flowers, 0.03, 0.25, 0, 0);
        }
        if (levelBiome.is(BiomeTags.IS_SAVANNA)) {
            return new Cover(flowers, 0.01, 0.55, 0, 0);
        }
        if (levelBiome.is(BiomeTags.IS_JUNGLE)) {
            return new Cover(flowers, 0.02, 0.5, 0.4, 0);
        }
        if (levelBiome.is(BiomeTags.IS_TAIGA) || levelBiome.is(Tags.Biomes.IS_CONIFEROUS)) {
            return new Cover(flowers, 0.01, 0.35, 0.6, 0);
        }
        if (levelBiome.is(BiomeTags.IS_FOREST)) {
            return new Cover(flowers, 0.03, 0.25, 0, 0);
        }
        if (levelBiome.is(BiomeTags.IS_BADLANDS) || levelBiome.is(Tags.Biomes.IS_DESERT)) {
            return new Cover(List.of(), 0, 0, 0, 0.02);
        }
        if (levelBiome.is(BiomeTags.IS_BEACH) || levelBiome.is(BiomeTags.IS_OCEAN) || levelBiome.is(BiomeTags.IS_RIVER)) {
            return new Cover(List.of(), 0, 0, 0, 0);
        }
        return new Cover(flowers, 0.015, 0.3, 0, 0);
    }

    private static boolean is(Holder<Biome> biome, ResourceKey<Biome> key) {
        return biome.is(key);
    }
}
