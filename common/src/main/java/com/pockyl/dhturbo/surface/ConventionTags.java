package com.pockyl.dhturbo.surface;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import com.pockyl.dhturbo.platform.Platform;

/** The biome tags modded biomes are recognised by. On this Minecraft version each loader has its own, with its own names. */
final class ConventionTags {
    static final TagKey<Biome> IS_CONIFEROUS_TREE = biome("is_coniferous", "tree_coniferous");
    static final TagKey<Biome> IS_DENSE_VEGETATION_OVERWORLD = biome("is_dense/overworld", "vegetation_dense");
    static final TagKey<Biome> IS_MUSHROOM = biome("is_mushroom", "mushroom");
    static final TagKey<Biome> IS_DESERT = biome("is_desert", "desert");
    /** Fabric's tags have no sandy biomes of their own. */
    static final TagKey<Biome> IS_SANDY = biome("is_sandy", "desert");

    private ConventionTags() {
    }

    private static TagKey<Biome> biome(String forgePath, String fabricPath) {
        return TagKey.create(Registries.BIOME, Platform.INSTANCE.biomeTag(forgePath, fabricPath));
    }
}
