package com.pockyl.dhturbo.world;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.block.Block;

import com.pockyl.dhturbo.platform.Platform;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The part of {@link WorldgenLoader} that depends on the Minecraft version: filling the registries from the packs. */
final class WorldgenRegistries {
    private WorldgenRegistries() {
    }

    static RegistryAccess.Frozen load(ResourceManager packs, LayeredRegistryAccess<RegistryLayer> layers) {
        RegistryAccess.Frozen worldgen = RegistryDataLoader.load(packs, layers.getAccessForLoading(RegistryLayer.WORLDGEN),
                Platform.INSTANCE.worldgenRegistries());
        // Tags too (a structure's biomes are a biome tag), as the server binds them after loading.
        worldgen.registries().forEach(entry -> bindTags(packs, entry));
        return worldgen;
    }

    static HolderGetter<Block> blocks() {
        return BuiltInRegistries.BLOCK.asLookup();
    }

    private static <T> void bindTags(ResourceManager packs, RegistryAccess.RegistryEntry<T> entry) {
        Registry<T> registry = entry.value();
        TagLoader<Holder<T>> loader = new TagLoader<>(registry::getHolder, Registries.tagsDirPath(entry.key()));
        Map<TagKey<T>, List<Holder<T>>> tags = new HashMap<>();
        loader.loadAndBuild(packs).forEach((id, holders) -> tags.put(TagKey.create(entry.key(), id), List.copyOf(holders)));
        registry.bindTags(tags);
    }
}
