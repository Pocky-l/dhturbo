package com.pockyl.dhturbo.surface;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/** The biome tags modded biomes are recognised by. Both loaders fill the same {@code c:} tags. */
final class ConventionTags {
    static final TagKey<Biome> IS_CONIFEROUS_TREE = biome("is_tree/coniferous");
    static final TagKey<Biome> IS_DENSE_VEGETATION_OVERWORLD = biome("is_dense_vegetation/overworld");
    static final TagKey<Biome> IS_MUSHROOM = biome("is_mushroom");
    static final TagKey<Biome> IS_DESERT = biome("is_desert");
    static final TagKey<Biome> IS_SANDY = biome("is_sandy");

    private ConventionTags() {
    }

    private static TagKey<Biome> biome(String path) {
        return TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("c", path));
    }
}
